package com.blackmarlin.downloader.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.blackmarlin.downloader.BmdGraph
import com.blackmarlin.downloader.BuildConfig
import com.blackmarlin.downloader.bridge.NativeFailure
import com.blackmarlin.downloader.domain.DownloadPolicy
import com.blackmarlin.downloader.domain.PublicationRules
import com.blackmarlin.downloader.platform.TransferCoordinator
import com.blackmarlin.downloader.platform.TransferNotifications
import com.blackmarlin.downloader.ui.*
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** All UI/notification commands pass through the same durable command boundary. */
class BmdRepository(private val graph: BmdGraph) : BmdUiStore {
    private val dao = graph.db.tasks()
    private val mutable = MutableStateFlow(BmdAppState(versionName = BuildConfig.VERSION_NAME, engineRevision = BuildConfig.ENGINE_REVISION))
    override val state: StateFlow<BmdAppState> = mutable.asStateFlow()
    private val refresh = MutableStateFlow(0L)
    private val initGate = Mutex()
    private var collecting = false
    @Volatile private var storage = UiStorage()
    @Volatile private var remembered: List<UiCredentialSummary> = emptyList()
    @Volatile private var startupMessage: String? = null

    suspend fun initialize() = initGate.withLock {
        if (collecting) return@withLock
        try {
            graph.ensureReady()
            refreshAuxiliary()
        } catch (_: Exception) {
            startupMessage = "Local task storage could not be recovered. Your saved files have not been changed."
        }
        collecting = true
        graph.scope.launch {
            combine(dao.observeTasks(), dao.observeFiles(), dao.observePublications(), graph.preferences.state, refresh) { tasks, files, publications, settings, _ ->
                val attempts = publications.associateBy { it.taskId }
                val fileByTask = files.associateBy { it.taskId }
                BmdAppState(tasks.map { present(it, attempts[it.id], fileByTask[it.id]) }, files.map(::present), settings,
                    storage.copy(publishedBytes = files.sumOf { it.bytes }), remembered, loading = false, admissionNotice = startupMessage,
                    notificationPermissionGranted = graph.notifications.permissionGranted,
                    transferChannelEnabled = graph.notifications.channelEnabled(TransferNotifications.TRANSFERS),
                    completionChannelEnabled = graph.notifications.channelEnabled(TransferNotifications.COMPLETED),
                    versionName = BuildConfig.VERSION_NAME, engineRevision = BuildConfig.ENGINE_REVISION)
            }.catch { mutable.value = mutable.value.copy(loading = false, admissionNotice = "Task storage is unavailable. Restart Black Marlin to recover it.") }
                .collect { mutable.value = it }
        }
        graph.scope.launch {
            try { graph.engine.initialize() }
            catch (_: Exception) { startupMessage = TransferCoordinator.safeError("ENGINE_UNAVAILABLE"); refresh.value++ }
        }
    }

    override suspend fun inspectLink(request: InspectLinkRequest): LinkReview = withContext(Dispatchers.IO) {
        graph.ensureReady()
        val source = DownloadPolicy.parse(request.url, request.allowPlainHttp)
        val credential = request.credential ?: if (source.secure) graph.credentials.remembered(source.origin) else null
        credential?.let { DownloadPolicy.validateCredential(source, it) }
        val name = DownloadPolicy.filename(runCatching { URI(source.url).path.substringAfterLast('/').ifBlank { "download" } }.getOrDefault("download"))
        val duplicate = dao.duplicate(source.url)?.id
        val network = graph.networks.select(request.networkPolicy, request.allowMeteredOnce)
        if (network == null) {
            if (request.networkPolicy == NetworkPolicy.UNMETERED && !request.allowMeteredOnce && graph.networks.anyNetwork()) {
                throw BmdUiException("Only a metered network is available. Allow it for this download or wait for an unmetered connection.", BmdUiException.Recovery.METERED_CONSENT)
            }
            return@withContext LinkReview(source.url, source.origin, name, warning = "Offline. The file will be checked when an allowed network is available.", existingTaskId = duplicate)
        }
        val lease = graph.networks.admit(network, request.networkPolicy, request.allowMeteredOnce)
            ?: throw BmdUiException("The allowed network changed. Check the link again.")
        var handle = 0L
        var joined = false
        try {
            val command = JSONObject().put("command", "probe").put("taskId", "probe-${UUID.randomUUID()}").put("generation", 1)
                .put("url", source.url).put("expectedOrigin", source.origin).put("credentialOrigin", credential?.origin)
                .put("httpConsent", request.allowPlainHttp).put("authorization", graph.credentials.authorization(credential))
                .put("network", JSONObject().put("handle", lease.handle).put("generation", lease.generation)).put("maxConnections", 1)
            withContext(NonCancellable) {
                if (!lease.valid.get() || !graph.networks.eligible(network, request.networkPolicy, request.allowMeteredOnce)) throw NativeFailure("NETWORK_UNAVAILABLE")
                handle = graph.engine.command(command).getLong("handle")
            }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!lease.valid.get() || SystemClock.elapsedRealtime() >= deadline) throw BmdUiException("The source did not respond on the allowed network. Try checking the link again.")
                val snapshot = graph.engine.snapshot(handle)
                if (snapshot.terminal && snapshot.writerReleased) {
                    if (snapshot.phase == "probed") return@withContext LinkReview(source.url, source.origin,
                        DownloadPolicy.filename(snapshot.fileName ?: name), totalBytes = snapshot.totalBytes, supportsResume = snapshot.resumable,
                        possibleHtmlPage = snapshot.webPage, existingTaskId = duplicate,
                        warning = if (snapshot.webPage) "This link appears to return a web page. Use a direct file link for the intended file." else null)
                    if (snapshot.errorCode == "SOURCE_AUTH_REQUIRED") return@withContext LinkReview(source.url, source.origin, name,
                        requiresCredentials = true, existingTaskId = duplicate, warning = "The source requires HTTPS credentials.")
                    throw BmdUiException(TransferCoordinator.safeError(snapshot.errorCode ?: "PROBE_FAILED"))
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } catch (failure: NativeFailure) {
            throw BmdUiException(TransferCoordinator.safeError(failure.code))
        } finally {
            withContext(NonCancellable) {
                if (handle == 0L) joined = true else runCatching {
                    graph.engine.stop(handle)
                    val deadline = SystemClock.elapsedRealtime() + 30_000
                    while (true) {
                        val end = graph.engine.snapshot(handle)
                        if (end.terminal && end.writerReleased) { graph.engine.release(handle); joined = true; break }
                        if (end.terminal || SystemClock.elapsedRealtime() >= deadline) break
                        delay(100)
                    }
                }
                if (joined) graph.networks.release(lease)
            }
        }
    }

    override suspend fun execute(command: BmdCommand): BmdResult = withContext(Dispatchers.IO + NonCancellable) {
        graph.ensureReady()
        val result = when (command) {
            BmdCommand.RefreshSystemState -> { refreshFileAvailability(); BmdResult() }
            is BmdCommand.StartDownload -> start(command.request)
            is BmdCommand.TaskAction -> action(command)
            is BmdCommand.ChangeDestination -> changeDestination(command)
            is BmdCommand.LocateSavedFile -> locate(command)
            is BmdCommand.SaveCredential -> saveCredential(command)
            is BmdCommand.ForgetCredential -> { graph.credentials.forget(command.origin); BmdResult(message = "Remembered credential removed. Active task credentials are kept until that task ends.") }
            is BmdCommand.UpdateSettings -> { graph.preferences.update(command.settings); BmdResult(message = "Settings saved. Transfer limits apply to the next admitted run.") }
            is BmdCommand.RemoveFiles -> removeFiles(command)
            is BmdCommand.RemoveTransfers -> removeTransfers(command.taskIds, false)
            is BmdCommand.CleanupPartials -> removeTransfers(command.taskIds, true)
        }
        refreshAuxiliary()
        refresh.value++
        result
    }

    private fun requireVisible() { DownloadPolicy.requireSafe(graph.visibility.visible, "Open Black Marlin to start or resume this transfer.") }
    private suspend fun start(request: StartDownloadRequest): BmdResult {
        requireVisible()
        DownloadPolicy.requireSafe(request.submissionId.matches(Regex("[A-Za-z0-9_-]{1,100}")), "This download request expired. Check the link again.")
        val source = DownloadPolicy.parse(request.review.url, request.allowPlainHttp)
        val name = DownloadPolicy.filename(request.fileName)
        val hash = DownloadPolicy.expectedHash(request.expectedSha256)
        val destination = persistDestination(request.destination, request.destinationGrantFlags)
        val credential = request.credential ?: if (source.secure) graph.credentials.remembered(source.origin) else null
        credential?.let { DownloadPolicy.validateCredential(source, it) }
        var created = false
        val task = graph.gate.withLock {
            dao.submitted(request.submissionId)?.let { return@withLock it }
            if (!request.saveAnotherCopy) dao.duplicate(source.url)?.let { throw BmdUiException("This link already has a transfer. Open it or explicitly choose another copy.") }
            val used = dao.tasks().map { it.jobId }.toSet()
            val jobId = (10_000..899_000).firstOrNull { it !in used } ?: throw BmdUiException("There are too many stored transfers. Clear completed history first.")
            val id = UUID.randomUUID().toString()
            credential?.let { graph.credentials.saveTask(id, it); if (it.rememberForOrigin) graph.credentials.remember(it) }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            val task = TaskEntity(id = id, submissionId = request.submissionId, name = name, sourceUrl = source.url, origin = source.origin,
                jobId = jobId, mimeType = mime, totalBytes = request.review.totalBytes?.takeIf { it >= 0 }, networkPolicy = request.networkPolicy.name,
                allowMeteredOnce = request.allowMeteredOnce, httpConsent = request.allowPlainHttp, expectedSha256 = hash,
                destinationKind = destination.kind.name, destinationUri = destination.treeUri, destinationLabel = destination.displayName)
            try { dao.insert(task) } catch (error: Exception) { graph.credentials.removeTask(id); throw error }
            created = true
            task
        }
        if (task.ownerSession == null && task.userIntent == "RUN" && !task.userResumeRequired &&
            task.stage in setOf("QUEUED", "WAITING_NETWORK", "WAITING_ANDROID")) graph.scheduler.schedule(task, true)
        return BmdResult(task.id, if (created) "Download added." else "This request is already in Transfers.")
    }

    private suspend fun action(command: BmdCommand.TaskAction): BmdResult {
        if (command.operation == TaskOperation.DISCARD_DOWNLOADED_DATA) return discardDownloadedData(command)
        val starts = command.operation !in setOf(TaskOperation.PAUSE, TaskOperation.CANCEL, TaskOperation.STOP_SAVING_KEEP_DATA)
        if (starts) requireVisible()
        if (command.operation == TaskOperation.SAVE_SEPARATE_COPY) {
            graph.publication.prepareSeparateCopy(command.taskId, command.expectedGeneration)
            return BmdResult(command.taskId, "The previous save was retained. Choose a destination, then tap Retry save to create the separate copy.")
        }
        val replacement = command.replacementRequest
        DownloadPolicy.requireSafe(replacement == null || command.operation == TaskOperation.REPLACE_LINK, "Replacement settings require an explicit Replace link confirmation.")
        val replacementSource = replacement?.let { DownloadPolicy.parse(it.review.url, it.allowPlainHttp) }
        val replacementName = replacement?.let { DownloadPolicy.filename(it.fileName) }
        val replacementHash = replacement?.let { DownloadPolicy.expectedHash(it.expectedSha256) }
        val replacementDestination = replacement?.let { persistDestination(it.destination, it.destinationGrantFlags) }
        val replacementCredential = replacement?.credential ?: replacementSource?.takeIf { it.secure }?.let { graph.credentials.remembered(it.origin) }
        if (replacementSource != null && replacementCredential != null) DownloadPolicy.validateCredential(replacementSource, replacementCredential)
        var old: TaskEntity? = null
        val changed = graph.gate.withLock {
            val task = dao.task(command.taskId) ?: throw BmdUiException("This transfer no longer exists.")
            if (command.expectedGeneration != null && command.expectedGeneration != task.runGeneration) throw BmdUiException("That action belongs to an earlier transfer run.")
            if (command.expectedScheduleEpoch != null && command.expectedScheduleEpoch != task.scheduleEpoch) throw BmdUiException("That action belongs to an earlier scheduled run.")
            DownloadPolicy.requireSafe(!DownloadPolicy.isFinal(task.stage), "This transfer has already finished.")
            val publication = dao.publication(task.id)
            old = task
            val next = if (!starts) {
                val cancel = command.operation == TaskOperation.CANCEL
                val local = task.verified || task.stage in BmdGraph.LOCAL_STAGES || publication != null
                val value = task.copy(userIntent = if (cancel) "CANCEL" else "PAUSE", scheduleEpoch = Math.addExact(task.scheduleEpoch, 1),
                    stage = if (task.ownerSession != null) if (cancel) "CANCELING" else "PAUSING" else if (local) "WAITING_SAVE" else if (cancel) "CANCELED" else "PAUSED",
                    cancelRequestedDuringSave = task.cancelRequestedDuringSave || (cancel && publication?.commitPlanned == true),
                    speed = 0, detail = if (local) "Downloaded data is retained." else if (cancel) "Transfer canceled." else "Transfer paused.", updatedAt = System.currentTimeMillis())
                dao.update(value)
                graph.coordinator.signalStop(task.id, if (cancel) "CANCEL" else "PAUSE")
                if (cancel && task.ownerSession == null && publication == null) {
                    graph.coordinator.cleanPrivateDirectory(task.id)
                    graph.credentials.removeTask(task.id)
                    value.copy(stage = "CANCELED", sourceUrl = "", verified = false)
                } else value
            } else {
                DownloadPolicy.requireSafe(task.ownerSession == null && !graph.coordinator.hasOwner(task.id), "Wait for the previous run to stop safely before starting again.")
                val restart = command.operation in setOf(TaskOperation.RESTART, TaskOperation.REPLACE_LINK)
                if (restart) {
                    DownloadPolicy.requireSafe(publication == null, "Resolve the previous save before discarding downloaded data.")
                    val source = replacementSource ?: DownloadPolicy.parse(command.replacementUrl ?: task.sourceUrl, if (command.replacementUrl != null) command.allowPlainHttp else task.httpConsent)
                    // Close the old episode durably before deleting its checkpoint or changing
                    // the task-scoped credential. A storage error leaves a paused, recoverable task.
                    dao.update(task.copy(userIntent = "PAUSE", stage = "PAUSED", scheduleEpoch = Math.addExact(task.scheduleEpoch, 1),
                        detail = "Restart preparation has not completed. Retry the confirmed action when ready.", updatedAt = System.currentTimeMillis()))
                    graph.scheduler.cancelLocked(task)
                    graph.coordinator.cleanPrivateDirectory(task.id)
                    if (source.origin != task.origin) graph.credentials.removeTask(task.id)
                    replacementCredential?.let { graph.credentials.saveTask(task.id, it); if (it.rememberForOrigin) graph.credentials.remember(it) }
                    task.copy(sourceUrl = source.url, origin = source.origin,
                        httpConsent = replacement?.allowPlainHttp ?: if (command.replacementUrl != null) command.allowPlainHttp else task.httpConsent,
                        name = replacementName ?: task.name,
                        mimeType = replacementName?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream" } ?: task.mimeType,
                        expectedSha256 = if (replacement != null) replacementHash else task.expectedSha256,
                        destinationKind = replacementDestination?.kind?.name ?: task.destinationKind,
                        destinationUri = if (replacementDestination != null) replacementDestination.treeUri else task.destinationUri,
                        destinationLabel = replacementDestination?.displayName ?: task.destinationLabel,
                        networkPolicy = replacement?.networkPolicy?.name ?: task.networkPolicy,
                        receivedBytes = 0, checkpointBytes = 0, totalBytes = replacement?.review?.totalBytes?.takeIf { it >= 0 }, verified = false, sha256Verified = false, saveBytes = 0,
                        recoveryCandidateUri = null, cancelRequestedDuringSave = false, stage = "QUEUED")
                } else {
                    DownloadPolicy.requireSafe(task.stage != "FILE_CHANGED", "Confirm Restart before discarding the old partial data.")
                    task.copy(stage = if (task.verified || task.stage in BmdGraph.LOCAL_STAGES || publication != null || command.operation in setOf(TaskOperation.RETRY_SAVE, TaskOperation.SAVE_SEPARATE_COPY)) "WAITING_SAVE" else "QUEUED")
                }.copy(userIntent = "RUN", userResumeRequired = false, scheduleEpoch = Math.addExact(task.scheduleEpoch, 1),
                    allowMeteredOnce = replacement?.allowMeteredOnce ?: if (command.operation == TaskOperation.REPLACE_LINK) command.allowMeteredOnce else task.allowMeteredOnce || command.allowMeteredOnce,
                    recoveryCandidateUri = if (restart || command.operation == TaskOperation.RETRY_SAVE) null else task.recoveryCandidateUri, errorCode = null, detail = null, updatedAt = System.currentTimeMillis())
            }
            dao.update(next)
            next
        }
        old?.let { graph.scheduler.cancel(it) }
        if (starts) graph.scheduler.schedule(changed, true)
        return BmdResult(changed.id, if (starts) "Transfer ready to continue." else "The stop request was saved.")
    }

    /** Explicit loss-of-recovery confirmation permits private cleanup, never provider deletion. */
    private suspend fun discardDownloadedData(command: BmdCommand.TaskAction): BmdResult {
        requireVisible()
        graph.gate.withLock {
            val task = dao.task(command.taskId) ?: throw BmdUiException("This transfer no longer exists.")
            DownloadPolicy.requireSafe(task.ownerSession == null && !graph.coordinator.hasOwner(task.id), "Wait for the current writer to stop before discarding its data.")
            DownloadPolicy.requireSafe(command.expectedGeneration == null || command.expectedGeneration == task.runGeneration, "That confirmation belongs to an earlier run.")
            DownloadPolicy.requireSafe(!DownloadPolicy.isFinal(task.stage), "This transfer has already finished.")
            graph.db.withTransaction {
                dao.publication(task.id)?.let { attempt ->
                    val snapshot = JSONObject().put("attemptId", attempt.attemptId).put("adapter", attempt.adapter).put("phase", attempt.phase)
                        .put("uri", attempt.uri).put("marker", attempt.marker).put("commitPlanned", attempt.commitPlanned)
                        .put("expectedBytes", attempt.expectedBytes).put("copiedBytes", attempt.copiedBytes).put("digest", attempt.digest)
                        .put("finalName", attempt.finalName).put("destinationUri", attempt.destinationUri).put("destinationLabel", attempt.destinationLabel)
                        .put("disposition", "USER_DISCARDED_PRIVATE_DATA").toString()
                    dao.archivePublication(PublicationHistoryEntity(attempt.attemptId, task.id, snapshot))
                    dao.removePublication(task.id)
                }
                dao.update(task.copy(stage = "CANCELED", userIntent = "CANCEL", scheduleEpoch = Math.addExact(task.scheduleEpoch, 1),
                    sourceUrl = "", recoveryCandidateUri = null, verified = false, sha256Verified = false, speed = 0,
                    detail = "Private data discarded. Any provider document was left unchanged.", updatedAt = System.currentTimeMillis()))
            }
            graph.scheduler.cancelLocked(task)
            graph.credentials.removeTask(task.id)
            graph.coordinator.cleanPrivateDirectory(task.id)
        }
        return BmdResult(command.taskId, "Downloaded private data discarded. Any document at the provider was left unchanged; manage it in Android's file manager.")
    }

    private fun persistDestination(input: UiDestination, flags: Int): UiDestination {
        if (input.kind == DestinationKind.PUBLIC_DOWNLOADS) {
            if (Build.VERSION.SDK_INT < 29) throw BmdUiException("Choose a folder with Android's picker on this Android version.", BmdUiException.Recovery.CHOOSE_DESTINATION)
            return UiDestination()
        }
        val uri = input.treeUri?.let(Uri::parse)
        DownloadPolicy.requireSafe(uri != null && uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri), "Choose a destination folder using Android's picker.")
        val permitted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (permitted != 0) try { graph.context.contentResolver.takePersistableUriPermission(uri!!, permitted) } catch (_: SecurityException) {
            throw BmdUiException("This provider did not grant lasting folder access. Choose another destination.", BmdUiException.Recovery.CHOOSE_DESTINATION)
        }
        val persisted = graph.context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission && it.isReadPermission }
        val folder = DocumentFile.fromTreeUri(graph.context, uri!!)
        DownloadPolicy.requireSafe(persisted && folder?.isDirectory == true && folder.canWrite(), "Folder access is unavailable. Choose the folder again.")
        return UiDestination(DestinationKind.DOCUMENT_TREE, folder?.name?.let(DownloadPolicy::filename) ?: "Selected folder", uri.toString())
    }

    private suspend fun changeDestination(command: BmdCommand.ChangeDestination): BmdResult {
        requireVisible()
        val destination = persistDestination(command.destination, command.receivedGrantFlags)
        if (command.taskId == null) graph.preferences.update(graph.preferences.state.value.copy(defaultDestination = destination))
        else graph.gate.withLock {
            val task = dao.task(command.taskId) ?: throw BmdUiException("This transfer no longer exists.")
            DownloadPolicy.requireSafe(task.ownerSession == null && !DownloadPolicy.isFinal(task.stage), "Stop the current save before choosing a different folder.")
            DownloadPolicy.requireSafe(dao.publication(task.id) == null, "The previous save has a recorded destination. Locate it or explicitly choose Save a separate copy before changing folders.")
            dao.update(task.copy(destinationKind = destination.kind.name, destinationUri = destination.treeUri, destinationLabel = destination.displayName, updatedAt = System.currentTimeMillis()))
        }
        return BmdResult(command.taskId, "Save location updated.")
    }

    private suspend fun locate(command: BmdCommand.LocateSavedFile): BmdResult {
        requireVisible()
        val uri = Uri.parse(command.candidateUri)
        DownloadPolicy.requireSafe(uri.scheme == ContentResolver.SCHEME_CONTENT, "Choose a document using Android's picker.")
        val flags = command.receivedGrantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (flags != 0) try { graph.context.contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) {
            throw BmdUiException("This provider cannot retain access to that document. Choose a document with lasting read access.")
        }
        DownloadPolicy.requireSafe(graph.context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }, "Choose the saved document again to grant lasting read access.")
        val task = graph.gate.withLock {
            val old = dao.task(command.taskId) ?: throw BmdUiException("This transfer no longer exists.")
            DownloadPolicy.requireSafe(old.verified && old.ownerSession == null && !DownloadPolicy.isFinal(old.stage), "Wait for the retained download to be verified and the current save to stop.")
            old.copy(recoveryCandidateUri = command.candidateUri, stage = "WAITING_SAVE", userIntent = "RUN", userResumeRequired = false,
                scheduleEpoch = Math.addExact(old.scheduleEpoch, 1), errorCode = null, updatedAt = System.currentTimeMillis()).also { dao.update(it) }
        }
        graph.scheduler.scheduleLocal(task, true)
        return BmdResult(task.id, "Checking the chosen document against the retained download.")
    }

    private suspend fun saveCredential(command: BmdCommand.SaveCredential): BmdResult {
        requireVisible()
        val value = command.credential
        if (command.taskId == null) {
            DownloadPolicy.requireSafe(value.rememberForOrigin, "Choose Remember for this origin to store a reusable credential.")
            graph.credentials.remember(value)
        } else graph.gate.withLock {
            val task = dao.task(command.taskId) ?: throw BmdUiException("This transfer no longer exists.")
            DownloadPolicy.requireSafe(task.ownerSession == null && !DownloadPolicy.isFinal(task.stage), "Stop the transfer before replacing its source credential.")
            DownloadPolicy.validateCredential(DownloadPolicy.parse(task.sourceUrl, task.httpConsent), value)
            graph.credentials.saveTask(task.id, value)
            if (value.rememberForOrigin) graph.credentials.remember(value)
        }
        return BmdResult(command.taskId, "Source credential saved. Tap Resume to continue.")
    }

    private suspend fun removeFiles(command: BmdCommand.RemoveFiles): BmdResult {
        val failed = mutableMapOf<String, String>()
        for (id in command.fileIds) {
            try {
                val file = dao.file(id) ?: continue
                if (command.deleteFromDevice) {
                    val uri = Uri.parse(file.uri)
                    val removed = if (DocumentsContract.isDocumentUri(graph.context, uri)) DocumentsContract.deleteDocument(graph.context.contentResolver, uri)
                        else graph.context.contentResolver.delete(uri, null, null) > 0
                    DownloadPolicy.requireSafe(removed, "The provider did not delete this file. It remains in your list.")
                }
                graph.gate.withLock { dao.removeFile(id) }
            } catch (_: Exception) { failed[id] = if (command.deleteFromDevice) "This file could not be deleted. Check Android's file manager or restore access." else "This item could not be removed from the list." }
        }
        return BmdResult(message = if (command.deleteFromDevice) "Selected files processed." else "Selected items removed from Files.", failedItems = failed)
    }

    private suspend fun removeTransfers(ids: Set<String>, cleanupOnly: Boolean): BmdResult {
        val failed = mutableMapOf<String, String>()
        for (id in ids) try {
            graph.gate.withLock {
                val task = dao.task(id) ?: return@withLock
                val publication = dao.publication(id)
                DownloadPolicy.requireSafe(task.ownerSession == null && !graph.coordinator.hasOwner(id) &&
                    PublicationRules.mayCleanStage(publication?.phase, true), "Stop this transfer and resolve its save attempt before cleaning it up.")
                DownloadPolicy.requireSafe(task.stage !in setOf("QUEUED", "CONNECTING", "DOWNLOADING", "SAVING", "VERIFYING"), "Pause or cancel this transfer before removing it.")
                graph.scheduler.cancelLocked(task)
                graph.coordinator.cleanPrivateDirectory(id)
                graph.credentials.removeTask(id)
                if (cleanupOnly) dao.update(task.copy(stage = if (task.stage == "COMPLETE") "COMPLETE" else "CANCELED", userIntent = "CANCEL", sourceUrl = "", verified = false, detail = "Private downloaded data removed.", updatedAt = System.currentTimeMillis()))
                else graph.db.withTransaction { dao.removePublication(id); dao.removePublicationHistory(id); dao.removeTask(id) }
            }
        } catch (_: Exception) { failed[id] = "This transfer still owns downloaded data or an unresolved save. Stop it and resolve the save first." }
        return BmdResult(message = if (cleanupOnly) "Private data cleanup finished." else "Transfer history updated. Saved files were kept.", failedItems = failed)
    }

    private suspend fun refreshFileAvailability() {
        for (file in dao.files()) {
            val uri = Uri.parse(file.uri)
            // Opening a cloud provider's stream can trigger a fresh remote download. Refresh
            // checks grants, and only queries Android's local MediaStore for missing entries.
            val accessible = if (uri.authority == "media") try {
                graph.context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } ?: false
            } catch (_: Exception) { false } else graph.context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (accessible != file.accessible) dao.updateFile(file.copy(accessible = accessible))
        }
    }
    private suspend fun refreshAuxiliary() {
        val tasks = dao.tasks()
        val sizes = tasks.associate { it.id to directorySize(graph.directory(it.id)) }
        val candidates = mutableListOf<UiCleanupItem>()
        for (task in tasks) if (task.ownerSession == null && PublicationRules.mayCleanStage(dao.publication(task.id)?.phase, true) &&
            task.stage !in setOf("QUEUED", "CONNECTING", "DOWNLOADING", "SAVING", "VERIFYING") && sizes.getValue(task.id) > 0) {
            candidates += UiCleanupItem(task.id, task.name, sizes.getValue(task.id), "Retained private download data")
        }
        storage = UiStorage(sizes.values.sum(), dao.files().sumOf { it.bytes }, candidates.sumOf { it.bytes },
            runCatching { StatFs(graph.context.noBackupFilesDir.absolutePath).availableBytes }.getOrNull(), candidates)
        remembered = graph.credentials.summaries()
    }
    private fun directorySize(directory: File): Long = runCatching {
        if (!directory.isDirectory || java.nio.file.Files.isSymbolicLink(directory.toPath())) 0L else
            directory.listFiles().orEmpty().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .sumOf { runCatching { Math.multiplyExact(android.system.Os.stat(it.absolutePath).st_blocks, 512L) }.getOrDefault(it.length()) }
    }.getOrDefault(0)

    override suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        graph.ensureReady()
        buildString {
            appendLine("Black Marlin ${BuildConfig.VERSION_NAME}")
            appendLine("Source ${BuildConfig.ENGINE_REVISION}")
            appendLine("Android API ${Build.VERSION.SDK_INT}; ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Notifications granted: ${graph.notifications.permissionGranted}")
            for (task in dao.tasks()) {
                appendLine("Task ${task.id}: ${task.stage}; generation ${task.runGeneration}; durable bytes ${task.checkpointBytes}; error ${task.errorCode ?: "none"}; publication ${dao.publication(task.id)?.phase ?: "none"}")
            }
        }
    }

    private fun present(task: TaskEntity, publication: PublicationEntity?, file: SavedFileEntity?): UiTask {
        val stage = runCatching { TransferStage.valueOf(task.stage) }.getOrDefault(TransferStage.INTERRUPTED)
        val local = task.verified || task.stage in BmdGraph.LOCAL_STAGES || publication != null && publication.phase !in setOf("PUBLISHED", "CLEANED")
        val actions = mutableSetOf<TaskOperation>()
        if (!DownloadPolicy.isFinal(task.stage)) {
            if (task.ownerSession != null) {
                if (task.userIntent == "RUN") actions += if (local) TaskOperation.STOP_SAVING_KEEP_DATA else TaskOperation.PAUSE
                if (task.userIntent != "CANCEL") actions += TaskOperation.CANCEL
            } else if (local) {
                actions += TaskOperation.RETRY_SAVE
                actions += TaskOperation.STOP_SAVING_KEEP_DATA
                if (publication != null && task.verified) actions += TaskOperation.SAVE_SEPARATE_COPY
                actions += TaskOperation.CANCEL
                actions += TaskOperation.DISCARD_DOWNLOADED_DATA
            } else {
                actions += if (stage == TransferStage.FILE_CHANGED) TaskOperation.RESTART else TaskOperation.RESUME
                if (stage in setOf(TransferStage.NEEDS_LINK, TransferStage.FAILED, TransferStage.FILE_CHANGED)) actions += TaskOperation.REPLACE_LINK
                actions += TaskOperation.CANCEL
            }
        }
        return UiTask(task.id, task.name, task.origin, stage, task.receivedBytes, task.totalBytes, task.speed.takeIf { it > 0 },
            if (task.speed > 0 && task.totalBytes != null) ((task.totalBytes - task.receivedBytes).coerceAtLeast(0) / task.speed) else null,
            task.saveBytes.takeIf { local || stage == TransferStage.COMPLETE }, task.totalBytes, task.mimeType,
            UiDestination(DestinationKind.valueOf(task.destinationKind), task.destinationLabel, task.destinationUri), NetworkPolicy.valueOf(task.networkPolicy),
            task.createdAt, task.updatedAt, task.detail, task.errorCode, actions, file?.id,
            publication != null && publication.phase !in setOf("PUBLISHED", "CLEANED"), task.expectedSha256, task.sha256Verified,
            task.savedBeforeCancellation, runGeneration = task.runGeneration)
    }
    private fun present(file: SavedFileEntity) = UiFile(file.id, file.taskId, file.name, file.mimeType, file.bytes, file.savedAt,
        file.destinationLabel, file.uri, file.accessible, file.sha256Verified)
}
