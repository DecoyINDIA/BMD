package com.blackmarlin.downloader.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BmdScreen {
    TRANSFERS, ADD_LINK, REVIEW, DESTINATION, TRANSFER_DETAIL, FILES, FILE_DETAIL,
    SEARCH, SETTINGS, NETWORK, STORAGE, APPEARANCE, NOTIFICATIONS,
    CREDENTIALS, HELP, ABOUT,
}
data class BmdRoute(val screen: BmdScreen, val id: String? = null, val context: String? = null)
enum class TransferFilter { ALL, ACTIVE, ATTENTION }
enum class SelectionKind { TRANSFERS, FILES }

data class DownloadDraft(
    val url: String = "",
    val fileName: String = "",
    val review: LinkReview? = null,
    val destination: UiDestination = UiDestination(),
    val destinationGrantFlags: Int = 0,
    val networkPolicy: NetworkPolicy = NetworkPolicy.UNMETERED,
    val networkExplicit: Boolean = false,
    val destinationExplicit: Boolean = false,
    val expectedSha256: String = "",
    val credential: UiCredentialInput? = null,
    val allowPlainHttp: Boolean = false,
    val allowMeteredOnce: Boolean = false,
    val submissionId: String = UUID.randomUUID().toString(),
    val replacementTaskId: String? = null,
    val error: String? = null,
)

sealed interface BmdDialog {
    data class PlainHttp(val origin: String) : BmdDialog
    data object Metered : BmdDialog
    data object Notifications : BmdDialog
    data class CancelTask(val taskId: String, val name: String, val generation: Long) : BmdDialog
    data class RestartTask(val taskId: String, val name: String, val replacement: Boolean, val generation: Long,
        val draftSubmissionId: String? = null, val replacementUrl: String? = null,
        val replacementRequest: StartDownloadRequest? = null) : BmdDialog
    data class Duplicate(val taskId: String) : BmdDialog
    data class RemoveFiles(val ids: Set<String>, val delete: Boolean) : BmdDialog
    data class RemoveTransfers(val ids: Set<String>) : BmdDialog
    data class Cleanup(val ids: Set<String>, val bytes: Long) : BmdDialog
    data class ForgetCredential(val origin: String) : BmdDialog
    data class SharedLinks(val links: List<String>) : BmdDialog
    data class SeparateCopy(val taskId: String, val generation: Long) : BmdDialog
    data class StopSaving(val taskId: String, val generation: Long) : BmdDialog
    data class DiscardDownloadedData(val taskId: String, val name: String, val generation: Long) : BmdDialog
    data class BatchFailures(val failures: Map<String, String>) : BmdDialog
    data object DiscardDraft : BmdDialog
}

data class BmdSession(
    val route: BmdRoute = BmdRoute(BmdScreen.TRANSFERS),
    val draft: DownloadDraft = DownloadDraft(),
    val dialog: BmdDialog? = null,
    val busy: Boolean = false,
    val searchQuery: String = "",
    val fileQuery: String = "",
    val transferFilter: TransferFilter = TransferFilter.ALL,
    val fileKind: FileKind = FileKind.ALL,
    val fileSort: FileSort = FileSort.NEWEST,
    val selectionKind: SelectionKind? = null,
    val selectedIds: Set<String> = emptySet(),
    val credentialOrigin: String = "",
    val credentialTaskId: String? = null,
    /** Kept in ViewModel memory across rotation, never in SavedStateHandle. */
    val credentialForm: UiCredentialInput? = null,
    val diagnostics: String? = null,
    val diagnosticsLoading: Boolean = false,
)

sealed interface BmdEffect {
    data class Message(val text: String) : BmdEffect
    data object ReadClipboard : BmdEffect
    data class PickDestination(val target: String, val draftSubmissionId: String? = null) : BmdEffect
    data class LocateSavedFile(val taskId: String) : BmdEffect
    data object RequestNotificationPermission : BmdEffect
    data class OpenNotificationSettings(val channelId: String? = null) : BmdEffect
    data class OpenFile(val file: UiFile) : BmdEffect
    data class ShareFile(val file: UiFile) : BmdEffect
    data class ShareReport(val text: String) : BmdEffect
    data class OpenExternalLink(val url: String) : BmdEffect
    data class UpdateSystemBars(val darkTheme: Boolean) : BmdEffect
    data class ProtectCredentialWindow(val enabled: Boolean) : BmdEffect
}

/** Commands are serialized at the store; this class only owns navigation and transient forms. */
class BmdViewModel(
    private val store: BmdUiStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val appState: StateFlow<BmdAppState> = store.state
    private val routes = mutableListOf(BmdRoute(BmdScreen.TRANSFERS))
    private val effectChannel = Channel<BmdEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val mutableSession = MutableStateFlow(BmdSession(
        route = restoreRoute(),
        draft = DownloadDraft(
            url = boundSharedText(savedState.get<String>("draftUrl")).orEmpty(),
            fileName = savedState["draftName"] ?: "",
            expectedSha256 = savedState["draftSha"] ?: "",
            submissionId = savedState["draftSubmissionId"] ?: UUID.randomUUID().toString(),
            replacementTaskId = savedState["draftReplacementId"],
            networkPolicy = runCatching { NetworkPolicy.valueOf(savedState["draftNetwork"] ?: "UNMETERED") }.getOrDefault(NetworkPolicy.UNMETERED),
            networkExplicit = savedState["draftNetworkExplicit"] ?: false,
            destinationExplicit = savedState["draftDestinationExplicit"] ?: false,
            destination = UiDestination(
                kind = runCatching { DestinationKind.valueOf(savedState["draftDestinationKind"] ?: "PUBLIC_DOWNLOADS") }.getOrDefault(DestinationKind.PUBLIC_DOWNLOADS),
                displayName = savedState["draftDestinationName"] ?: "Downloads",
                treeUri = savedState["draftDestinationUri"],
            ),
            destinationGrantFlags = savedState["draftDestinationFlags"] ?: 0,
            // A process-restored draft asks for source and metered consent again.
        ),
    ))
    val session: StateFlow<BmdSession> = mutableSession.asStateFlow()
    private var pendingConsent: (() -> Unit)? = null
    private var initializedDefaults = false
    private var probeJob: Job? = null

    init {
        val restored = mutableSession.value.route
        val base = when {
            restored.screen == BmdScreen.FILE_DETAIL || restored.screen == BmdScreen.FILES -> BmdScreen.FILES
            restored.screen in setOf(BmdScreen.SETTINGS, BmdScreen.NETWORK, BmdScreen.STORAGE, BmdScreen.APPEARANCE,
                BmdScreen.NOTIFICATIONS, BmdScreen.HELP, BmdScreen.ABOUT) ||
                (restored.screen in setOf(BmdScreen.CREDENTIALS, BmdScreen.DESTINATION) && restored.context == "settings") -> BmdScreen.SETTINGS
            else -> BmdScreen.TRANSFERS
        }
        routes.clear()
        routes += BmdRoute(base)
        if (restored.screen == BmdScreen.CREDENTIALS && restored.context != "settings") {
            routes += if (restored.id != null) BmdRoute(BmdScreen.TRANSFER_DETAIL, restored.id) else BmdRoute(BmdScreen.ADD_LINK)
        }
        if (restored.screen != base) routes += restored
        persistDraft()
        viewModelScope.launch {
            appState.collect { state ->
                if (!state.loading && !initializedDefaults) {
                    initializedDefaults = true
                    mutableSession.update { it.copy(draft = it.draft.copy(
                        networkPolicy = if (it.draft.networkExplicit) it.draft.networkPolicy else state.settings.defaultNetwork,
                        destination = if (it.draft.destinationExplicit) it.draft.destination else state.settings.defaultDestination,
                    )) }
                    persistDraft()
                }
                val selected = mutableSession.value.selectedIds
                if (selected.isNotEmpty()) {
                    val available = if (mutableSession.value.selectionKind == SelectionKind.FILES)
                        state.files.map { it.id }.toSet() else state.tasks.map { it.id }.toSet()
                    mutableSession.update { it.copy(selectedIds = selected.intersect(available)) }
                }
            }
        }
    }

    private fun restoreRoute(): BmdRoute {
        val screen = runCatching { BmdScreen.valueOf(savedState["screen"] ?: "TRANSFERS") }.getOrDefault(BmdScreen.TRANSFERS)
        // Probe metadata and passwords are not written into SavedStateHandle.
        return BmdRoute(if (screen == BmdScreen.REVIEW) BmdScreen.ADD_LINK else screen,
            savedState["routeId"], savedState["routeContext"])
    }

    fun navigate(screen: BmdScreen, id: String? = null, context: String? = null) {
        val route = BmdRoute(screen, id, context)
        if (routes.lastOrNull() != route) routes += route
        setRoute(route)
        if (screen == BmdScreen.STORAGE) refreshSystemState()
    }

    fun selectTab(screen: BmdScreen) {
        routes.clear()
        routes += BmdRoute(screen)
        setRoute(routes.last())
        mutableSession.update { it.copy(selectionKind = null, selectedIds = emptySet()) }
    }

    fun back(): Boolean {
        if (mutableSession.value.dialog != null) { dismissDialog(); return true }
        if (mutableSession.value.selectionKind != null) { clearSelection(); return true }
        probeJob?.cancel()
        if (mutableSession.value.route.screen == BmdScreen.CREDENTIALS) mutableSession.update { it.copy(credentialForm = null) }
        if (routes.size <= 1) {
            if (mutableSession.value.route.screen !in setOf(BmdScreen.TRANSFERS, BmdScreen.FILES, BmdScreen.SETTINGS)) {
                selectTab(BmdScreen.TRANSFERS)
                return true
            }
            return false
        }
        routes.removeAt(routes.lastIndex)
        setRoute(routes.last())
        return true
    }

    private fun setRoute(route: BmdRoute) {
        mutableSession.update { it.copy(route = route) }
        savedState["screen"] = route.screen.name
        savedState["routeId"] = route.id
        savedState["routeContext"] = route.context
    }

    fun addLink() = navigate(BmdScreen.ADD_LINK)
    fun setDraftUrl(value: String) {
        val bounded = boundSharedText(value).orEmpty()
        probeJob?.cancel()
        pendingConsent = null
        mutableSession.update { it.copy(dialog = null) }
        updateDraft { copy(url = bounded, fileName = if (replacementTaskId == null) "" else fileName,
            expectedSha256 = if (replacementTaskId == null) "" else expectedSha256, review = null, allowPlainHttp = false, allowMeteredOnce = false,
            credential = null, error = null, submissionId = UUID.randomUUID().toString()) }
    }
    fun setDraftName(value: String) = updateDraft { copy(fileName = value, error = null) }
    fun setExpectedSha(value: String) = updateDraft { copy(expectedSha256 = value, error = null) }
    fun setDraftNetwork(value: NetworkPolicy) = updateDraft { copy(networkPolicy = value, networkExplicit = true, allowMeteredOnce = false) }
    fun pasteLink() = emit(BmdEffect.ReadClipboard)
    fun pastedText(text: String?) {
        val bounded = boundSharedText(text)
        if (bounded.isNullOrBlank()) { message("The clipboard does not contain a link."); return }
        val links = sharedHttpLinks(bounded)
        when {
            links.size > 1 -> showDialog(BmdDialog.SharedLinks(links))
            links.size == 1 -> setDraftUrl(links.first())
            else -> setDraftUrl(bounded.trim())
        }
    }

    fun incomingShare(text: String) {
        val bounded = boundSharedText(text).orEmpty()
        val links = sharedHttpLinks(bounded)
        // A new share invalidates earlier probes/consents even before a multi-link choice.
        discardDraft()
        navigate(BmdScreen.ADD_LINK)
        when {
            links.size > 1 -> showDialog(BmdDialog.SharedLinks(links))
            links.size == 1 -> setDraftUrl(links.first())
            else -> { setDraftUrl(bounded.trim()); draftError("Share a direct HTTP or HTTPS file link.") }
        }
    }

    fun chooseSharedLink(url: String) { dismissDialog(); setDraftUrl(url) }
    fun requestDiscardDraft() = showDialog(BmdDialog.DiscardDraft)
    fun discardDraft() {
        probeJob?.cancel()
        pendingConsent = null
        mutableSession.update { it.copy(dialog = null, draft = DownloadDraft(
            destination = appState.value.settings.defaultDestination,
            networkPolicy = appState.value.settings.defaultNetwork,
        )) }
        persistDraft()
    }

    fun reviewLink() {
        if (mutableSession.value.busy) return
        val draft = mutableSession.value.draft
        val requestRoute = mutableSession.value.route
        fun isCurrentRequest() = mutableSession.value.draft.submissionId == draft.submissionId &&
            mutableSession.value.draft.url == draft.url && mutableSession.value.route == requestRoute
        val origin = parseSourceOrigin(draft.url)
        if (origin == null) { draftError("Enter a complete HTTP or HTTPS link without a username or password in the address."); return }
        if (origin.startsWith("http://") && !draft.allowPlainHttp) {
            pendingConsent = { if (isCurrentRequest()) { updateDraft { copy(allowPlainHttp = true) }; reviewLink() } }
            showDialog(BmdDialog.PlainHttp(origin))
            return
        }
        mutableSession.update { it.copy(busy = true, draft = it.draft.copy(error = null)) }
        probeJob = viewModelScope.launch {
            try {
                val review = store.inspectLink(InspectLinkRequest(draft.url.trim(), draft.networkPolicy,
                    draft.allowMeteredOnce, draft.allowPlainHttp, draft.credential))
                if (!isCurrentRequest()) return@launch
                updateDraft { copy(review = review, fileName = fileName.ifBlank { review.suggestedName }) }
                navigate(BmdScreen.REVIEW)
            } catch (error: CancellationException) { throw error
            } catch (error: BmdUiException) {
                if (!isCurrentRequest()) return@launch
                if (error.recovery == BmdUiException.Recovery.METERED_CONSENT) {
                    pendingConsent = { if (isCurrentRequest()) { updateDraft { copy(allowMeteredOnce = true) }; reviewLink() } }
                    showDialog(BmdDialog.Metered)
                } else {
                    draftError(error.userMessage)
                    if (error.recovery == BmdUiException.Recovery.SOURCE_CREDENTIALS) openCredentials(origin)
                }
            } catch (_: Exception) { if (isCurrentRequest()) draftError("The source could not be checked. Check the connection and try again.")
            } finally { mutableSession.update { it.copy(busy = false) }; probeJob = null }
        }
    }

    fun allowPlainHttp() {
        completeConsent()
    }
    fun allowMeteredOnce() {
        completeConsent()
    }
    fun waitForUnmetered() {
        updateDraft { copy(networkPolicy = NetworkPolicy.UNMETERED, networkExplicit = true, allowMeteredOnce = false) }
        dismissDialog()
        message("Waiting for an unmetered connection. Review this link when it is available.")
    }
    private fun completeConsent() {
        val pending = pendingConsent
        pendingConsent = null
        mutableSession.update { it.copy(dialog = null) }
        pending?.invoke()
    }

    fun startDownload(saveAnother: Boolean = false) {
        if (mutableSession.value.busy) return
        val draft = mutableSession.value.draft
        val review = draft.review ?: run { reviewLink(); return }
        if (draft.fileName.trim().isEmpty() || draft.fileName.contains('/') || draft.fileName.contains('\\')) {
            draftError("Use a file name without slashes."); return
        }
        if (!isValidExpectedSha256(draft.expectedSha256)) { draftError("A SHA-256 checksum must contain exactly 64 hexadecimal characters."); return }
        if (!draft.destination.available) { draftError("Choose an available save location."); return }
        val request = StartDownloadRequest(draft.submissionId, review, draft.fileName.trim(), draft.destination,
            draft.networkPolicy, draft.expectedSha256.trim().lowercase().ifEmpty { null }, draft.credential,
            draft.allowMeteredOnce, draft.allowPlainHttp, saveAnother, draft.destinationGrantFlags)
        if (draft.replacementTaskId != null) {
            val task = appState.value.tasks.find { it.id == draft.replacementTaskId } ?: return
            showDialog(BmdDialog.RestartTask(task.id, task.name, replacement = true, generation = task.runGeneration,
                draftSubmissionId = draft.submissionId, replacementUrl = draft.url.trim(), replacementRequest = request))
            return
        }
        if (review.existingTaskId != null && !saveAnother) {
            showDialog(BmdDialog.Duplicate(review.existingTaskId)); return
        }
        runCommand(BmdCommand.StartDownload(request), onSuccess = { result ->
            if (session.value.draft.submissionId == request.submissionId) {
                discardDraft()
                selectTab(BmdScreen.TRANSFERS)
                result.taskId?.let { navigate(BmdScreen.TRANSFER_DETAIL, it) }
                if (!appState.value.notificationPermissionGranted) showDialog(BmdDialog.Notifications)
            } else message("Download added to Transfers.")
        }, onMetered = {
            if (session.value.draft.submissionId == request.submissionId) {
                updateDraft { copy(allowMeteredOnce = true) }; startDownload(saveAnother)
            }
        }, draftSubmissionId = request.submissionId)
    }

    fun taskAction(taskId: String, operation: TaskOperation) {
        val task = appState.value.tasks.find { it.id == taskId } ?: run { message("This transfer is no longer in the list."); return }
        when (operation) {
            TaskOperation.CANCEL -> showDialog(BmdDialog.CancelTask(task.id, task.name, task.runGeneration))
            TaskOperation.RESTART -> showDialog(BmdDialog.RestartTask(task.id, task.name, false, task.runGeneration))
            TaskOperation.REPLACE_LINK -> {
                discardDraft()
                updateDraft { copy(replacementTaskId = taskId, fileName = task.name,
                    destination = task.destination, networkPolicy = task.networkPolicy, networkExplicit = true, destinationExplicit = true,
                    expectedSha256 = task.expectedSha256.orEmpty()) }
                navigate(BmdScreen.ADD_LINK)
            }
            TaskOperation.SAVE_SEPARATE_COPY -> showDialog(BmdDialog.SeparateCopy(taskId, task.runGeneration))
            TaskOperation.STOP_SAVING_KEEP_DATA -> showDialog(BmdDialog.StopSaving(taskId, task.runGeneration))
            TaskOperation.DISCARD_DOWNLOADED_DATA -> showDialog(BmdDialog.DiscardDownloadedData(taskId, task.name, task.runGeneration))
            else -> sendTaskCommand(taskId, operation)
        }
    }

    fun confirmTaskAction(taskId: String, operation: TaskOperation, expectedGeneration: Long? = null) {
        dismissDialog()
        sendTaskCommand(taskId, operation, expectedGeneration = expectedGeneration)
    }
    private fun sendTaskCommand(taskId: String, operation: TaskOperation, replacementUrl: String? = null, meteredConsent: Boolean = false,
        expectedGeneration: Long? = null, replacementRequest: StartDownloadRequest? = null) {
        val task = appState.value.tasks.find { it.id == taskId } ?: return
        if (expectedGeneration != null && task.runGeneration != expectedGeneration) {
            message("This transfer changed. Review its current status before choosing that action again."); return
        }
        val draft = mutableSession.value.draft
        val replacementDraftId = replacementRequest?.submissionId ?: if (replacementUrl != null) draft.submissionId else null
        val actionRoute = session.value.route
        runCommand(BmdCommand.TaskAction(taskId, operation, replacementUrl,
            replacementRequest?.allowPlainHttp ?: (replacementUrl != null && draft.allowPlainHttp),
            meteredConsent || replacementRequest?.allowMeteredOnce == true || (replacementUrl != null && draft.allowMeteredOnce),
            task.runGeneration, replacementRequest), onSuccess = {
            if (replacementUrl != null) {
                if (session.value.draft.submissionId == replacementDraftId) {
                    discardDraft(); selectTab(BmdScreen.TRANSFERS); navigate(BmdScreen.TRANSFER_DETAIL, taskId)
                } else message("The replacement download was requested. Your new draft is still open.")
            } else if (operation == TaskOperation.SAVE_SEPARATE_COPY && session.value.route == actionRoute) {
                navigate(BmdScreen.DESTINATION, context = "separate:$taskId")
            } else if (operation == TaskOperation.RETRY_SAVE && session.value.route.context == "separate:$taskId") {
                selectTab(BmdScreen.TRANSFERS); navigate(BmdScreen.TRANSFER_DETAIL, taskId)
            }
        }, onMetered = {
            if (appState.value.tasks.find { it.id == taskId }?.runGeneration == task.runGeneration &&
                (replacementDraftId == null || session.value.draft.submissionId == replacementDraftId))
                sendTaskCommand(taskId, operation, replacementUrl, meteredConsent = true, expectedGeneration = task.runGeneration,
                    replacementRequest = replacementRequest?.copy(allowMeteredOnce = true))
        }, draftSubmissionId = replacementDraftId)
    }

    fun confirmRestart(dialog: BmdDialog.RestartTask) {
        dismissDialog()
        if (dialog.replacement) {
            if (session.value.draft.submissionId != dialog.draftSubmissionId || session.value.draft.url.trim() != dialog.replacementUrl) {
                message("The replacement draft changed. Review the new link before restarting."); return
            }
            sendTaskCommand(dialog.taskId, TaskOperation.REPLACE_LINK, dialog.replacementUrl, expectedGeneration = dialog.generation,
                replacementRequest = dialog.replacementRequest)
        } else sendTaskCommand(dialog.taskId, TaskOperation.RESTART, expectedGeneration = dialog.generation)
    }

    fun destination(target: String = "draft") = navigate(BmdScreen.DESTINATION, context = target)
    fun pickDestination(target: String) = emit(BmdEffect.PickDestination(target, if (target == "draft") session.value.draft.submissionId else null))
    fun destinationChosen(target: String, destination: UiDestination, flags: Int = 0, expectedDraftSubmissionId: String? = null) {
        if (target == "draft") {
            if (expectedDraftSubmissionId != null && expectedDraftSubmissionId != session.value.draft.submissionId) {
                message("That folder was chosen for a previous draft. Choose a location for this new link."); return
            }
            updateDraft { copy(destination = destination, destinationGrantFlags = flags, destinationExplicit = true) }
            if (session.value.route.screen == BmdScreen.DESTINATION && session.value.route.context == target) back()
        } else runCommand(BmdCommand.ChangeDestination(if (target == "settings") null else target.removePrefix("separate:"), destination, flags),
            onSuccess = { if (!target.startsWith("separate:") && session.value.route.screen == BmdScreen.DESTINATION && session.value.route.context == target) back() })
    }
    fun locateSavedFile(taskId: String) = emit(BmdEffect.LocateSavedFile(taskId))
    fun savedFileLocated(taskId: String, uri: String, flags: Int) =
        runCommand(BmdCommand.LocateSavedFile(taskId, uri, flags))

    fun openCredentials(origin: String = "", taskId: String? = null, fromSettings: Boolean = false) {
        val current = session.value.draft.credential?.takeIf { it.origin == origin && taskId == null && !fromSettings }
        mutableSession.update { it.copy(credentialOrigin = origin, credentialTaskId = taskId,
            credentialForm = current ?: UiCredentialInput(origin, CredentialKind.NONE)) }
        navigate(BmdScreen.CREDENTIALS, taskId, if (fromSettings || origin.isBlank()) "settings" else "source")
    }
    fun updateCredentialForm(input: UiCredentialInput) { mutableSession.update { it.copy(credentialForm = input) } }
    fun saveCredential(input: UiCredentialInput) {
        val parsed = parseSourceOrigin(input.origin)
        val originUri = runCatching { URI(input.origin.trim()) }.getOrNull()
        if (parsed == null || !parsed.startsWith("https://") || originUri == null ||
            (!originUri.path.isNullOrEmpty() && originUri.path != "/") || originUri.rawQuery != null || originUri.rawFragment != null) {
            message("Enter the exact HTTPS origin, including a non-default port. Do not include a path."); return
        }
        val credential = input.copy(origin = parsed)
        if (input.kind != CredentialKind.NONE && input.secret.isBlank()) { message("Enter the source credential."); return }
        val taskId = mutableSession.value.credentialTaskId
        val settings = mutableSession.value.route.context == "settings"
        if (settings && input.kind == CredentialKind.NONE) { runCommand(BmdCommand.ForgetCredential(parsed), onSuccess = { back() }); return }
        if (settings && !input.rememberForOrigin) { message("Choose Remember for this origin to store a credential for future downloads."); return }
        if (!settings && taskId == null) {
            val expected = parseSourceOrigin(mutableSession.value.draft.url)
            if (expected != parsed) { message("This credential must match the download's exact source."); return }
            updateDraft { copy(credential = credential, error = null) }
            back()
            reviewLink()
        } else {
            runCommand(BmdCommand.SaveCredential(taskId, credential), onSuccess = {
                back()
                message(if (taskId == null) "Credential saved for this exact origin." else "Source credential updated. Resume when you are ready.")
            })
        }
    }
    fun requestForgetCredential(origin: String) = showDialog(BmdDialog.ForgetCredential(origin))
    fun forgetCredential(origin: String) { dismissDialog(); runCommand(BmdCommand.ForgetCredential(origin)) }

    fun updateSettings(settings: UiSettings) = runCommand(BmdCommand.UpdateSettings(settings))
    fun resetNetworkSettings() = updateSettings(appState.value.settings.copy(defaultNetwork = NetworkPolicy.UNMETERED,
        maxActiveTransfers = 2, segmentsPerTransfer = 2, globalSegmentLimit = 4, perHostLimit = 2, speedLimitBytesPerSecond = 0))
    fun refreshSystemState() = runCommand(BmdCommand.RefreshSystemState, silent = true)
    fun requestNotifications() = showDialog(BmdDialog.Notifications)
    fun allowNotifications() { dismissDialog(); emit(BmdEffect.RequestNotificationPermission) }
    fun openNotificationSettings(channel: String? = null) = emit(BmdEffect.OpenNotificationSettings(channel))
    fun openExternal(url: String) = emit(BmdEffect.OpenExternalLink(url))
    fun openFile(file: UiFile) = emit(BmdEffect.OpenFile(file))
    fun shareFile(file: UiFile) = emit(BmdEffect.ShareFile(file))

    fun setTransferFilter(value: TransferFilter) { mutableSession.update { it.copy(transferFilter = value) } }
    fun setFileKind(value: FileKind) { mutableSession.update { it.copy(fileKind = value) } }
    fun setFileSort(value: FileSort) { mutableSession.update { it.copy(fileSort = value) } }
    fun setSearchQuery(value: String) { mutableSession.update { it.copy(searchQuery = value) } }
    fun setFileQuery(value: String) { mutableSession.update { it.copy(fileQuery = value) } }

    fun beginSelection(kind: SelectionKind, id: String? = null) {
        mutableSession.update { it.copy(selectionKind = kind, selectedIds = id?.let { setOf(it) } ?: emptySet()) }
    }
    fun toggleSelection(id: String) { mutableSession.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) } }
    fun selectAll(ids: Set<String>) { mutableSession.update { it.copy(selectedIds = ids) } }
    fun clearSelection() { mutableSession.update { it.copy(selectionKind = null, selectedIds = emptySet()) } }

    fun bulkTaskAction(operation: TaskOperation) {
        val ids = mutableSession.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            mutableSession.update { it.copy(busy = true) }
            val failures = linkedMapOf<String, String>()
            for (id in ids) {
                val task = appState.value.tasks.find { it.id == id } ?: continue
                if (operation !in task.permittedActions) continue
                try {
                    val result = store.execute(BmdCommand.TaskAction(id, operation, expectedGeneration = task.runGeneration))
                    failures.putAll(result.failedItems)
                } catch (error: CancellationException) { throw error
                } catch (error: BmdUiException) { failures[id] = error.userMessage
                } catch (_: Exception) { failures[id] = "This action could not complete." }
            }
            mutableSession.update { it.copy(busy = false, selectedIds = failures.keys,
                selectionKind = if (failures.isEmpty()) null else it.selectionKind,
                dialog = if (failures.isEmpty()) null else BmdDialog.BatchFailures(failures)) }
        }
    }

    fun requestRemoveFiles(ids: Set<String>, delete: Boolean) { if (ids.isNotEmpty()) showDialog(BmdDialog.RemoveFiles(ids, delete)) }
    fun removeFiles(ids: Set<String>, delete: Boolean) {
        dismissDialog()
        runCommand(BmdCommand.RemoveFiles(ids, delete), onSuccess = { result ->
            if (result.failedItems.isEmpty()) { clearSelection(); if (session.value.route.screen == BmdScreen.FILE_DETAIL) back() }
        })
    }
    fun requestRemoveTransfers(ids: Set<String>) { if (ids.isNotEmpty()) showDialog(BmdDialog.RemoveTransfers(ids)) }
    fun removeTransfers(ids: Set<String>) { dismissDialog(); runCommand(BmdCommand.RemoveTransfers(ids), onSuccess = { clearSelection() }) }
    fun requestCleanup(ids: Set<String>) {
        if (ids.isEmpty()) return
        val bytes = appState.value.storage.cleanupCandidates.filter { it.taskId in ids }.sumOf { it.bytes }
        showDialog(BmdDialog.Cleanup(ids, bytes))
    }
    fun cleanup(ids: Set<String>) { dismissDialog(); runCommand(BmdCommand.CleanupPartials(ids)) }

    fun loadDiagnostics() {
        if (session.value.diagnosticsLoading) return
        mutableSession.update { it.copy(diagnosticsLoading = true) }
        viewModelScope.launch {
            try { val report = store.diagnostics(); mutableSession.update { it.copy(diagnostics = report) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { message("The diagnostic report could not be created.")
            } finally { mutableSession.update { it.copy(diagnosticsLoading = false) } }
        }
    }
    fun shareDiagnostics() { session.value.diagnostics?.let { emit(BmdEffect.ShareReport(it)) } }
    fun message(text: String) = emit(BmdEffect.Message(text))
    fun showDialog(dialog: BmdDialog) { mutableSession.update { it.copy(dialog = dialog) } }
    fun dismissDialog() { pendingConsent = null; mutableSession.update { it.copy(dialog = null) } }

    private fun updateDraft(block: DownloadDraft.() -> DownloadDraft) {
        mutableSession.update { it.copy(draft = it.draft.block()) }
        persistDraft()
    }
    private fun persistDraft() {
        val draft = mutableSession.value.draft
        savedState["draftUrl"] = boundSharedText(draft.url).orEmpty()
        savedState["draftName"] = draft.fileName
        savedState["draftSha"] = draft.expectedSha256
        savedState["draftSubmissionId"] = draft.submissionId
        savedState["draftReplacementId"] = draft.replacementTaskId
        savedState["draftNetwork"] = draft.networkPolicy.name
        savedState["draftNetworkExplicit"] = draft.networkExplicit
        savedState["draftDestinationExplicit"] = draft.destinationExplicit
        savedState["draftDestinationKind"] = draft.destination.kind.name
        savedState["draftDestinationName"] = draft.destination.displayName
        savedState["draftDestinationUri"] = draft.destination.treeUri
        savedState["draftDestinationFlags"] = draft.destinationGrantFlags
    }
    private fun draftError(message: String) { mutableSession.update { it.copy(draft = it.draft.copy(error = message)) } }
    private fun emit(effect: BmdEffect) { viewModelScope.launch { effectChannel.send(effect) } }

    private fun runCommand(
        command: BmdCommand,
        silent: Boolean = false,
        onSuccess: (BmdResult) -> Unit = {},
        onMetered: (() -> Unit)? = null,
        draftSubmissionId: String? = null,
    ) {
        if (!silent && mutableSession.value.busy) return
        if (!silent) mutableSession.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = store.execute(command)
                onSuccess(result)
                if (result.failedItems.isNotEmpty()) showDialog(BmdDialog.BatchFailures(result.failedItems))
                result.message?.takeIf { !silent }?.let { message(it) }
            } catch (error: CancellationException) { throw error
            } catch (error: BmdUiException) {
                if (draftSubmissionId != null && session.value.draft.submissionId != draftSubmissionId) return@launch
                if (error.recovery == BmdUiException.Recovery.METERED_CONSENT && onMetered != null) {
                    pendingConsent = onMetered; showDialog(BmdDialog.Metered)
                } else if (!silent) message(error.userMessage)
            } catch (_: Exception) {
                if (!silent) message("This action could not complete. Your saved progress has been kept.")
            } finally { if (!silent) mutableSession.update { it.copy(busy = false) } }
        }
    }
}
