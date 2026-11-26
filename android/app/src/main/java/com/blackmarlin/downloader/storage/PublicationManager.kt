package com.blackmarlin.downloader.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import com.blackmarlin.downloader.data.BmdDatabase
import com.blackmarlin.downloader.data.PublicationEntity
import com.blackmarlin.downloader.data.PublicationHistoryEntity
import com.blackmarlin.downloader.data.SavedFileEntity
import com.blackmarlin.downloader.data.TaskEntity
import com.blackmarlin.downloader.domain.DownloadPolicy
import com.blackmarlin.downloader.ui.BmdUiException
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resumeWithException

data class PublicationOutcome(val saved: Boolean, val message: String? = null)

/**
 * Publishes immutable, verified private staging through a journaled stable Android URI.
 *
 * All callers own the task's executor claim. Provider I/O and callbacks happen outside [gate].
 * Only the short intent/journal/index transactions hold it. Nothing in this class downloads
 * network data, deletes private staging, or guesses a document from a display name or length.
 */
class PublicationManager internal constructor(
    private val context: Context,
    private val db: BmdDatabase,
    private val gate: Mutex,
    private val io: PublicationIo,
) {
    constructor(context: Context, db: BmdDatabase, gate: Mutex) : this(context, db, gate, AndroidPublicationIo(context))
    private val dao = db.tasks()

    suspend fun save(
        taskId: String,
        generation: Long,
        owner: String,
        isStopped: () -> Boolean,
        onProgress: suspend (Long) -> Unit,
    ): PublicationOutcome = withContext(Dispatchers.IO) {
        var writer: PublicationWriter? = null
        try {
            var task = ownedTask(taskId, generation, owner)
            val existingAttempt = dao.publication(taskId)
            if (existingAttempt?.phase in publishedPhases) return@withContext PublicationOutcome(true)
            val stage = stageFile(task)
            val expectedBytes = stage.length()
            if (existingAttempt != null && existingAttempt.expectedBytes != expectedBytes) throw IdentityUncertain("The retained file no longer matches this save attempt. Keep the data and inspect the destination.")
            checkRunning(isStopped)
            var freshlyCreated = false
            var attempt: PublicationEntity
            if (existingAttempt == null) {
                val id = UUID.randomUUID().toString()
                val adapter = if (task.destinationKind == "PUBLIC_DOWNLOADS") "MEDIASTORE" else "SAF"
                if (adapter == "MEDIASTORE" && Build.VERSION.SDK_INT < 29) throw SaveFailure("Choose a folder with Android's picker before saving on this Android version.")
                val planned = PublicationEntity(taskId, id, adapter, "CREATE_PLANNED",
                    marker = ".bmd-$id", expectedBytes = expectedBytes, runGeneration = generation,
                    ownerSession = owner, finalName = DownloadPolicy.filename(task.name), destinationUri = task.destinationUri,
                    destinationLabel = task.destinationLabel)
                gate.withLock {
                    db.withTransaction {
                        task = requireOwner(taskId, generation, owner)
                        requireRunIntent(task, isStopped)
                        if (dao.publication(taskId) != null) throw StaleOwner()
                        dao.putPublication(planned)
                    }
                }
                // An exception or process loss here leaves CREATE_PLANNED. Never create again blindly.
                val createdUri = io.create(task, planned, isStopped)
                attempt = withContext(NonCancellable) { recordCreatedUri(planned, createdUri, generation, owner) }
                freshlyCreated = true
            } else {
                attempt = journal(existingAttempt, generation, owner) { copy(runGeneration = generation, ownerSession = owner) }
                if (attempt.uri == null) {
                    // Only MediaStore can recover the exact app-owned pending attempt marker.
                    val recovered = if (attempt.adapter == "MEDIASTORE") io.recoverCreated(attempt, isStopped) else null
                    if (recovered == null) throw IdentityUncertain("The provider may have created a document, but its identity was not recorded. Locate the saved file or explicitly save a separate copy.")
                    attempt = recordCreatedUri(attempt, recovered, generation, owner)
                }
            }
            val uri = attempt.uri ?: throw IdentityUncertain("The save location needs to be resolved.")
            io.assertIdentity(attempt)

            if (attempt.commitPlanned || attempt.phase in completedCopyPhases) {
                // A close/finalization may have happened before the previous process ended.
                val proof = compare(stage, uri, allowPrefix = false, isStopped, onProgress)
                requireExpectedDigest(task, proof.digest)
                if (attempt.digest != null && attempt.digest != proof.digest) throw IdentityUncertain("The saved document no longer matches the recorded download. Its contents were left unchanged.")
                attempt = journal(attempt, generation, owner) { copy(phase = "COPY_COMPLETE", copiedBytes = proof.bytes, digest = proof.digest) }
                attempt = commitBarrier(attempt, generation, owner, isStopped)
                return@withContext finalizeKnownBytes(task, attempt, generation, owner, isStopped)
            }

            if (freshlyCreated) {
                val empty = compare(stage, uri, allowPrefix = true, isStopped, onProgress = {})
                if (empty.bytes != 0L) throw IdentityUncertain("The provider returned a nonempty document from create. It was left unchanged.")
            } else {
                // Own URI + exact prefix proof is required before any destructive truncate.
                val prefix = compare(stage, uri, allowPrefix = true, isStopped, onProgress = {})
                if (prefix.complete) {
                    requireExpectedDigest(task, prefix.digest)
                    attempt = journal(attempt, generation, owner) { copy(phase = "COPY_COMPLETE", copiedBytes = prefix.bytes, digest = prefix.digest) }
                    attempt = commitBarrier(attempt, generation, owner, isStopped)
                    return@withContext finalizeKnownBytes(task, attempt, generation, owner, isStopped)
                }
                if (attempt.adapter == "SAF") throw IdentityUncertain("This partial provider document cannot be safely overwritten after reopening. Locate it or explicitly save a separate copy.")
            }
            checkRunning(isStopped)
            gate.withLock { requireRunIntent(requireOwner(taskId, generation, owner), isStopped) }
            attempt = journal(attempt, generation, owner) { copy(phase = "COPYING", copiedBytes = 0) }
            // Recovered documents must prove seek/truncate support. A fresh created stream may be sequential.
            writer = io.openWrite(attempt, requireSafeTruncate = !freshlyCreated, isStopped)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            var copied = 0L
            var lastReport = 0L
            FileInputStream(stage).use { input ->
                while (true) {
                    checkRunning(isStopped)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    writer!!.write(buffer, 0, read, isStopped)
                    digest.update(buffer, 0, read)
                    copied = Math.addExact(copied, read.toLong())
                    if (copied > expectedBytes) throw IdentityUncertain("The private download changed while it was being saved.")
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 250 || copied == expectedBytes) {
                        dao.saveProgress(taskId, generation, owner, copied, System.currentTimeMillis())
                        onProgress(copied)
                        lastReport = now
                    }
                }
            }
            if (copied != expectedBytes) throw IdentityUncertain("The private download changed while it was being saved.")
            writer!!.flushDurably()
            val checksum = digest.digest().hex()
            requireExpectedDigest(task, checksum)
            attempt = journal(attempt, generation, owner) { copy(phase = "COPY_COMPLETE", copiedBytes = copied, digest = checksum) }
            // This is the serialized winner against Cancel. It precedes successful close/finalization.
            attempt = commitBarrier(attempt, generation, owner, isStopped)
            withContext(NonCancellable) {
                writer!!.closeSuccessfully()
                writer = null
                attempt = journal(attempt, generation, owner) { copy(phase = "PROVIDER_CLOSED") }
            }
            // Generic SAF providers can buffer, replace, or truncate on close. Read back actual bytes.
            // A stop/timeout after CommitPlanned leaves the stage for subsequent reconciliation.
            if (attempt.adapter != "MEDIASTORE") {
                val proof = compare(stage, uri, allowPrefix = false, isStopped, onProgress = {})
                if (proof.digest != checksum) throw IdentityUncertain("The provider did not preserve the downloaded bytes. The private download is retained.")
            } else io.assertClosedLength(attempt)
            finalizeKnownBytes(task, attempt, generation, owner, isStopped)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { writer?.abort() }
                hold(taskId, generation, owner, "Saving was interrupted. The downloaded data is retained.")
            }
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { writer?.abort() } }
            if (failure is StaleOwner) return@withContext PublicationOutcome(false, "A newer task action owns this transfer.")
            val message = safeFailure(failure)
            hold(taskId, generation, owner, message, forceUnresolved = failure is IdentityUncertain || failure is ByteMismatch,
                invalidStage = failure is LocalIntegrityFailure)
            PublicationOutcome(false, message)
        } finally {
            withContext(NonCancellable) { runCatching { writer?.abort() } }
        }
    }

    suspend fun locate(
        taskId: String,
        generation: Long,
        owner: String,
        candidateUri: String,
        stop: () -> Boolean,
        onProgress: suspend (Long) -> Unit,
    ): PublicationOutcome = withContext(Dispatchers.IO) {
        try {
            if (Uri.parse(candidateUri).scheme != ContentResolver.SCHEME_CONTENT) throw SaveFailure("Choose a document using Android's picker.")
            val task = ownedTask(taskId, generation, owner)
            val stage = stageFile(task)
            val old = dao.publication(taskId)
            if (old?.phase in publishedPhases) return@withContext PublicationOutcome(true)
            // No copy, truncate, rename or delete occurs while locating. Compare every actual byte.
            val proof = compare(stage, candidateUri, allowPrefix = false, stop, onProgress)
            requireExpectedDigest(task, proof.digest)
            val selected = if (old?.uri == candidateUri) old.copy(phase = "COPY_COMPLETE", digest = proof.digest,
                copiedBytes = proof.bytes, runGeneration = generation, ownerSession = owner)
            else PublicationEntity(taskId, UUID.randomUUID().toString(), "USER_SELECTED", "COPY_COMPLETE", candidateUri,
                marker = "user-selected", copiedBytes = proof.bytes, expectedBytes = proof.bytes, digest = proof.digest,
                runGeneration = generation, ownerSession = owner, finalName = task.name, destinationUri = task.destinationUri,
                destinationLabel = task.destinationLabel)
            gate.withLock {
                db.withTransaction {
                    requireRunIntent(requireOwner(taskId, generation, owner), stop)
                    if (old != null && old.attemptId != selected.attemptId) archive(old)
                    dao.putPublication(selected)
                }
            }
            val committed = commitBarrier(selected, generation, owner, stop)
            finalizeKnownBytes(task, committed, generation, owner, stop)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { hold(taskId, generation, owner, "The document check was interrupted. The downloaded data is retained.") }
            throw cancelled
        } catch (failure: Exception) {
            if (failure is StaleOwner) return@withContext PublicationOutcome(false, "A newer task action owns this transfer.")
            val message = if (failure is ByteMismatch) "The chosen document does not match the retained download. No document was changed or adopted." else safeFailure(failure)
            hold(taskId, generation, owner, message, invalidStage = failure is LocalIntegrityFailure)
            PublicationOutcome(false, message)
        }
    }

    /** Called only after the explicit duplicate-output warning, with no live executor owner. */
    suspend fun prepareSeparateCopy(taskId: String, expectedGeneration: Long? = null) = withContext(Dispatchers.IO) {
        gate.withLock {
            db.withTransaction {
                val task = dao.task(taskId) ?: throw BmdUiException("This transfer no longer exists.")
                if (expectedGeneration != null && task.runGeneration != expectedGeneration) throw BmdUiException("This transfer changed. Review its current save state before creating another copy.")
                if (task.ownerSession != null) throw BmdUiException("Wait for the previous save to stop before creating a separate copy.")
                val attempt = dao.publication(taskId)
                if (attempt?.phase in publishedPhases || task.stage == "COMPLETE") throw BmdUiException("This task is already saved. Start a new download if you want another copy.")
                if (!task.verified || !stageFile(task).isFile) throw BmdUiException("The retained downloaded data is not ready to save.")
                if (attempt != null) { archive(attempt); dao.removePublication(taskId) }
                dao.update(task.copy(stage = "WAITING_SAVE", userIntent = "PAUSE", userResumeRequired = true, saveBytes = 0,
                    recoveryCandidateUri = null,
                    detail = "A separate save was explicitly requested. The previous provider document, if any, was left unchanged.",
                    errorCode = null, cancelRequestedDuringSave = false, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun finalizeKnownBytes(task: TaskEntity, initial: PublicationEntity, generation: Long, owner: String, stop: () -> Boolean): PublicationOutcome {
        var attempt = initial
        // At this point bytes have been proved, and CommitPlanned already won. A later Cancel
        // cannot turn a real provider commit into a deleted comparison copy or an unindexed orphan.
        withContext(NonCancellable) {
            attempt = journal(attempt, generation, owner) { copy(phase = "PROVIDER_CLOSED") }
            io.finalizeDocument(attempt)
            attempt = journal(attempt, generation, owner) { copy(phase = "VISIBLE") }
            val metadata = io.metadata(attempt.uri!!)
            gate.withLock {
                db.withTransaction {
                    val latest = requireOwner(task.id, generation, owner)
                    val current = dao.publication(task.id) ?: throw StaleOwner()
                    if (current.attemptId != attempt.attemptId || !current.commitPlanned) throw StaleOwner()
                    val existing = dao.fileForTask(task.id)
                    if (existing != null && existing.uri != attempt.uri) throw IdentityUncertain("Another saved file is already indexed for this task.")
                    val file = SavedFileEntity(existing?.id ?: UUID.randomUUID().toString(), task.id,
                        DownloadPolicy.filename(metadata.name ?: attempt.finalName), attempt.uri!!, task.mimeType,
                        attempt.expectedBytes, attempt.destinationLabel, latest.sha256Verified)
                    if (existing == null) dao.insertFile(file) else dao.updateFile(file)
                    dao.putPublication(current.copy(phase = "PUBLISHED", copiedBytes = current.expectedBytes))
                    val canceledDuringSave = latest.userIntent == "CANCEL" || latest.cancelRequestedDuringSave
                    dao.update(latest.copy(stage = "COMPLETE", receivedBytes = current.expectedBytes,
                        saveBytes = current.expectedBytes, speed = 0, detail = if (canceledDuringSave) "Saved before cancellation" else null,
                        errorCode = null, savedBeforeCancellation = canceledDuringSave, updatedAt = System.currentTimeMillis()))
                }
            }
        }
        return PublicationOutcome(true, if (dao.task(task.id)?.savedBeforeCancellation == true) "Saved before cancellation. The file was preserved." else "File saved.")
    }

    private suspend fun commitBarrier(attempt: PublicationEntity, generation: Long, owner: String, stop: () -> Boolean): PublicationEntity = gate.withLock {
        db.withTransaction {
            val task = requireOwner(attempt.taskId, generation, owner)
            val current = dao.publication(task.id) ?: throw StaleOwner()
            if (current.attemptId != attempt.attemptId) throw StaleOwner()
            if (!current.commitPlanned) requireRunIntent(task, stop)
            if (current.digest == null || current.copiedBytes != current.expectedBytes) throw IdentityUncertain("Completed content proof is missing. The download is retained.")
            current.copy(phase = "COMMIT_PLANNED", commitPlanned = true, runGeneration = generation, ownerSession = owner)
                .also { dao.putPublication(it) }
        }
    }

    private suspend fun recordCreatedUri(attempt: PublicationEntity, uri: String, generation: Long, owner: String): PublicationEntity {
        val recorded = gate.withLock { db.withTransaction {
            if (Uri.parse(uri).scheme != ContentResolver.SCHEME_CONTENT) throw IdentityUncertain("The provider returned an unsupported document identity.")
            val recorded = attempt.copy(uri = uri, phase = "URI_RECORDED")
            val current = dao.publication(attempt.taskId)
            if (current?.attemptId != attempt.attemptId) {
                archive(recorded) // Never lose the returned URI even if an invalid owner change happened.
                return@withTransaction null
            }
            if (current.uri != null && current.uri != uri) throw IdentityUncertain("The provider returned conflicting document identities.")
            dao.putPublication(recorded)
            recorded
        } } ?: throw StaleOwner()
        // Check after committing identity: a lost owner must not roll back the returned URI.
        ownedTask(attempt.taskId, generation, owner)
        return recorded
    }

    private suspend fun journal(attempt: PublicationEntity, generation: Long, owner: String, change: PublicationEntity.() -> PublicationEntity): PublicationEntity = gate.withLock {
        db.withTransaction {
            requireOwner(attempt.taskId, generation, owner)
            val current = dao.publication(attempt.taskId) ?: throw StaleOwner()
            if (current.attemptId != attempt.attemptId) throw StaleOwner()
            current.change().also { dao.putPublication(it) }
        }
    }

    private suspend fun hold(taskId: String, generation: Long, owner: String, message: String, forceUnresolved: Boolean = false, invalidStage: Boolean = false) = gate.withLock {
        db.withTransaction {
            val task = dao.task(taskId) ?: return@withTransaction
            if (task.runGeneration != generation || task.ownerSession != owner || task.stage == "COMPLETE") return@withTransaction
            val attempt = dao.publication(taskId)
            val unresolved = forceUnresolved || invalidStage || attempt?.commitPlanned == true || (attempt != null && attempt.uri == null)
            if (unresolved && attempt != null) dao.putPublication(attempt.copy(phase = "UNRESOLVED"))
            dao.update(task.copy(stage = if (invalidStage) "FAILED" else "WAITING_SAVE", speed = 0, detail = message,
                verified = if (invalidStage) false else task.verified, sha256Verified = if (invalidStage) false else task.sha256Verified,
                errorCode = if (invalidStage) "LOCAL_INTEGRITY_MISMATCH" else if (unresolved) "SAVE_UNRESOLVED" else "SAVE_RETRY", updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun ownedTask(id: String, generation: Long, owner: String) = gate.withLock { requireOwner(id, generation, owner) }
    private suspend fun requireOwner(id: String, generation: Long, owner: String): TaskEntity {
        val task = dao.task(id) ?: throw StaleOwner()
        if (task.runGeneration != generation || task.ownerSession != owner) throw StaleOwner()
        return task
    }
    private fun requireRunIntent(task: TaskEntity, stop: () -> Boolean) { if (task.userIntent != "RUN" || stop()) throw SaveStopped() }

    private fun stageFile(task: TaskEntity): File {
        if (!task.verified || (task.expectedSha256 != null && !task.sha256Verified)) throw SaveFailure("This download must finish local verification before it can be saved.")
        if (!task.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) throw SaveFailure("The task has an invalid private storage identity.")
        val rawBase = File(context.noBackupFilesDir, "transfers")
        if (Files.isSymbolicLink(rawBase.toPath())) throw SaveFailure("The private staging directory is not a safe local directory.")
        val base = rawBase.canonicalFile
        val directory = File(base, task.id)
        val file = File(directory, "verified.payload")
        if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory.canonicalFile || directory.canonicalFile.parentFile != base || !file.isFile) {
            throw SaveFailure("The retained downloaded file is unavailable. No destination file was changed.")
        }
        if (task.totalBytes != null && file.length() != task.totalBytes) throw LocalIntegrityFailure("The retained download length no longer matches its completed-transfer proof. It cannot be published.")
        return file
    }

    private fun requireExpectedDigest(task: TaskEntity, digest: String) {
        if (task.expectedSha256 != null && !task.expectedSha256.equals(digest, ignoreCase = true))
            throw LocalIntegrityFailure("The retained download no longer matches the expected SHA-256. It cannot be published.")
    }

    private suspend fun compare(stage: File, uri: String, allowPrefix: Boolean, stop: () -> Boolean, onProgress: suspend (Long) -> Unit): ContentProof {
        val expectedBytes = stage.length()
        val digest = MessageDigest.getInstance("SHA-256")
        val actualBuffer = ByteArray(BUFFER_BYTES)
        val expectedBuffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var lastReport = 0L
        FileInputStream(stage).use { expected ->
            io.openRead(uri, stop).use { actual ->
                while (true) {
                    checkRunning(stop)
                    val count = actual.read(actualBuffer, stop)
                    if (count < 0) break
                    if (count == 0) continue
                    if (total > expectedBytes - count) throw ByteMismatch()
                    var filled = 0
                    while (filled < count) {
                        val read = expected.read(expectedBuffer, filled, count - filled)
                        if (read <= 0) throw ByteMismatch()
                        filled += read
                    }
                    var difference = 0
                    for (index in 0 until count) difference = difference or (actualBuffer[index].toInt() xor expectedBuffer[index].toInt())
                    if (difference != 0) throw ByteMismatch()
                    digest.update(expectedBuffer, 0, count)
                    total += count
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 250 || total == expectedBytes) { onProgress(total); lastReport = now }
                }
            }
        }
        if (!allowPrefix && total != expectedBytes) throw ByteMismatch()
        return ContentProof(total, digest.digest().hex(), total == expectedBytes)
    }

    private suspend fun archive(attempt: PublicationEntity) {
        val snapshot = JSONObject().put("taskId", attempt.taskId).put("attemptId", attempt.attemptId)
            .put("adapter", attempt.adapter).put("phase", attempt.phase).put("uri", attempt.uri)
            .put("marker", attempt.marker).put("copiedBytes", attempt.copiedBytes).put("expectedBytes", attempt.expectedBytes)
            .put("digest", attempt.digest).put("runGeneration", attempt.runGeneration).put("ownerSession", attempt.ownerSession)
            .put("commitPlanned", attempt.commitPlanned).put("finalName", attempt.finalName).put("destinationUri", attempt.destinationUri)
            .put("destinationLabel", attempt.destinationLabel)
            .put("createdAt", attempt.createdAt).toString()
        dao.archivePublication(PublicationHistoryEntity(attempt.attemptId, attempt.taskId, snapshot))
    }

    private fun safeFailure(error: Exception): String = when (error) {
        is IdentityUncertain -> error.message ?: "The previous save needs to be resolved. Downloaded data is retained."
        is ByteMismatch -> "The destination contents do not match this download. Nothing was overwritten; downloaded data is retained."
        is SaveFailure -> error.message ?: "Saving could not finish. Downloaded data is retained."
        is LocalIntegrityFailure -> error.message ?: "The retained download failed its content proof and cannot be published."
        is SaveStopped -> "Saving stopped. The downloaded data and save information are retained."
        is SecurityException -> "Access to the save location is no longer available. Grant access again or choose another location."
        else -> "The provider could not complete this save. Downloaded data is retained; retry or inspect the destination."
    }

    companion object {
        internal const val BUFFER_BYTES = 64 * 1024
        private val publishedPhases = setOf("PUBLISHED", "CLEANED")
        private val completedCopyPhases = setOf("COPY_COMPLETE", "COMMIT_PLANNED", "PROVIDER_CLOSED", "VISIBLE")
    }
}

internal data class ContentProof(val bytes: Long, val digest: String, val complete: Boolean)
internal data class DocumentMetadata(val name: String? = null, val bytes: Long? = null)
internal interface PublicationReader : Closeable { suspend fun read(buffer: ByteArray, stop: () -> Boolean): Int }
internal interface PublicationWriter {
    suspend fun write(buffer: ByteArray, offset: Int, count: Int, stop: () -> Boolean)
    fun flushDurably()
    fun closeSuccessfully()
    fun abort()
}
internal interface PublicationIo {
    suspend fun create(task: TaskEntity, attempt: PublicationEntity, stop: () -> Boolean): String
    suspend fun recoverCreated(attempt: PublicationEntity, stop: () -> Boolean): String?
    fun assertIdentity(attempt: PublicationEntity)
    suspend fun openWrite(attempt: PublicationEntity, requireSafeTruncate: Boolean, stop: () -> Boolean): PublicationWriter
    suspend fun openRead(uri: String, stop: () -> Boolean): PublicationReader
    fun assertClosedLength(attempt: PublicationEntity)
    fun finalizeDocument(attempt: PublicationEntity)
    fun metadata(uri: String): DocumentMetadata
}

/** Real ContentResolver adapter; provider URIs are never turned into filesystem paths. */
internal class AndroidPublicationIo(private val context: Context) : PublicationIo {
    private val resolver = context.contentResolver
    private val relativePath = Environment.DIRECTORY_DOWNLOADS + "/BlackMarlin/"

    override suspend fun create(task: TaskEntity, attempt: PublicationEntity, stop: () -> Boolean): String {
        checkRunning(stop)
        val uri = if (attempt.adapter == "MEDIASTORE") {
            if (Build.VERSION.SDK_INT < 29) throw SaveFailure("Choose a document folder on this Android version.")
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                // Insert creates a distinct row; the provider chooses a safe filename collision suffix.
                // Keep this URI and display name stable throughout publication; only pending changes.
                put(MediaStore.MediaColumns.DISPLAY_NAME, attempt.finalName)
                put(MediaStore.MediaColumns.TITLE, attempt.marker)
                put(MediaStore.MediaColumns.MIME_TYPE, task.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        } else {
            val tree = attempt.destinationUri?.let(Uri::parse) ?: throw SaveFailure("Choose an Android document folder before saving.")
            if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) throw SaveFailure("Choose a valid document folder using Android's picker.")
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(resolver, parent, task.mimeType, attempt.finalName)
        }
        return uri?.toString() ?: throw IdentityUncertain("The provider returned no document identity. Inspect the destination before trying another save.")
    }

    override suspend fun recoverCreated(attempt: PublicationEntity, stop: () -> Boolean): String? {
        if (Build.VERSION.SDK_INT < 29 || attempt.adapter != "MEDIASTORE") return null
        checkRunning(stop)
        val matches = mutableListOf<String>()
        queryMedia(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING),
            "${MediaStore.MediaColumns.TITLE}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(attempt.marker, relativePath))?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == context.packageName && cursor.getInt(2) == 1)
                    matches += ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()
            }
        }
        if (matches.size != 1) return null
        // No copy can start before URI_RECORDED, so a lost create result must still contain no bytes.
        openRead(matches.single(), stop).use { if (it.read(ByteArray(1), stop) != -1) return null }
        return matches.single()
    }

    override fun assertIdentity(attempt: PublicationEntity) {
        val uri = attempt.uri?.let(Uri::parse) ?: throw IdentityUncertain("The provider document identity is unknown.")
        if (uri.scheme != "content") throw IdentityUncertain("The provider document identity is unsupported.")
        if (attempt.adapter == "MEDIASTORE") {
            if (Build.VERSION.SDK_INT < 29) throw IdentityUncertain("This MediaStore save cannot be opened on this Android version.")
            queryMedia(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING))?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != context.packageName) throw IdentityUncertain("This document is no longer a proven app-owned save.")
                if (!attempt.commitPlanned && cursor.getInt(2) != 1) throw IdentityUncertain("The pending save changed outside this attempt. Inspect it before continuing.")
            } ?: throw IdentityUncertain("The pending save could not be identified.")
        } else if (attempt.adapter == "SAF") {
            val expectedId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrElse { throw IdentityUncertain("The provider changed the document identity.") }
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != expectedId) throw IdentityUncertain("The provider changed the document identity. The existing output was left unchanged.")
            } ?: throw IdentityUncertain("The created document could not be identified.")
        }
    }

    override suspend fun openWrite(attempt: PublicationEntity, requireSafeTruncate: Boolean, stop: () -> Boolean): PublicationWriter {
        assertIdentity(attempt)
        val descriptor = openDescriptor(Uri.parse(attempt.uri), if (requireSafeTruncate) "rw" else "w", stop)
        try {
            if (requireSafeTruncate) {
                // Do not request 'rwt': prove seeking before the destructive truncate happens.
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                Os.ftruncate(descriptor.fileDescriptor, 0)
                if (Os.fstat(descriptor.fileDescriptor).st_size != 0L) throw IOException("Truncate did not clear the document")
            }
            val regular = OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode)
            if (!regular) enableNonBlocking(descriptor)
            return DescriptorWriter(descriptor, regular)
        } catch (error: Exception) {
            runCatching { descriptor.closeWithError("BMD save was not admitted") }
            throw IdentityUncertain("This provider cannot safely restart the existing document. Locate it or explicitly save a separate copy.")
        }
    }

    override suspend fun openRead(uri: String, stop: () -> Boolean): PublicationReader {
        val descriptor = openDescriptor(Uri.parse(uri), "r", stop)
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode)) enableNonBlocking(descriptor)
            return DescriptorReader(descriptor)
        } catch (error: Exception) { runCatching { descriptor.close() }; throw error }
    }

    override fun assertClosedLength(attempt: PublicationEntity) {
        assertIdentity(attempt)
        val length = metadata(attempt.uri!!).bytes
        if (length == null || length != attempt.expectedBytes) throw IdentityUncertain("The provider has not confirmed the completed file length. The download is retained.")
    }

    override fun finalizeDocument(attempt: PublicationEntity) {
        if (attempt.adapter != "MEDIASTORE") return // SAF V1 deliberately has no rename call.
        if (Build.VERSION.SDK_INT < 29) throw IOException("MediaStore Downloads unavailable")
        assertIdentity(attempt)
        val uri = Uri.parse(attempt.uri)
        val updated = resolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
        if (updated != 1) throw IdentityUncertain("The provider did not confirm publication. The download is retained.")
        queryMedia(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME))?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(0) != 0 || cursor.getString(1) != context.packageName) throw IdentityUncertain("Publication could not be confirmed against the same document identity.")
        } ?: throw IdentityUncertain("Publication could not be confirmed against the same document identity.")
    }

    override fun metadata(uri: String): DocumentMetadata {
        val parsed = Uri.parse(uri)
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val result = if (parsed.authority == MediaStore.AUTHORITY) queryMedia(parsed, projection)
            else resolver.query(parsed, projection, null, null, null)
        return result?.use { cursor ->
            if (!cursor.moveToFirst()) DocumentMetadata()
            else DocumentMetadata(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getLong(1))
        } ?: DocumentMetadata()
    }

    @Suppress("DEPRECATION")
    private fun queryMedia(uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null): Cursor? {
        return if (Build.VERSION.SDK_INT >= 30) resolver.query(uri, projection, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }, null)
        else resolver.query(if (Build.VERSION.SDK_INT >= 29) MediaStore.setIncludePending(uri) else uri, projection, selection, args, null)
    }

    private suspend fun openDescriptor(uri: Uri, mode: String, stop: () -> Boolean): ParcelFileDescriptor {
        checkRunning(stop)
        val signal = CancellationSignal()
        val watcher = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
            while (isActive) { if (stop()) { signal.cancel(); break }; delay(50) }
        }
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    val descriptor = resolver.openFileDescriptor(uri, mode, signal) ?: throw IOException("Provider returned no file descriptor")
                    if (stop()) {
                        descriptor.close()
                        continuation.resumeWithException(SaveStopped())
                    } else continuation.resume(descriptor, onCancellation = { _, unclaimed, _ -> runCatching { unclaimed.close() } })
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        } finally { watcher.cancel() }
    }

    private fun enableNonBlocking(descriptor: ParcelFileDescriptor) {
        val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
}

internal class DescriptorReader(private val descriptor: ParcelFileDescriptor) : PublicationReader {
    override suspend fun read(buffer: ByteArray, stop: () -> Boolean): Int {
        while (true) {
            checkRunning(stop)
            try {
                val count = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                if (count == 0) { descriptor.checkError(); return -1 }
                return count
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                if (error.errno != OsConstants.EAGAIN) throw IOException("Provider read failed", error)
                delay(25)
            }
        }
    }
    override fun close() = descriptor.close()
}

internal class DescriptorWriter(private val descriptor: ParcelFileDescriptor, private val regular: Boolean) : PublicationWriter {
    private var closed = false
    override suspend fun write(buffer: ByteArray, offset: Int, count: Int, stop: () -> Boolean) {
        var written = 0
        while (written < count) {
            checkRunning(stop)
            try {
                val result = Os.write(descriptor.fileDescriptor, buffer, offset + written, count - written)
                if (result <= 0) throw IOException("Provider accepted no bytes")
                written += result
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                if (error.errno != OsConstants.EAGAIN) throw IOException("Provider write failed", error)
                delay(25)
            }
        }
    }
    override fun flushDurably() { if (regular) Os.fsync(descriptor.fileDescriptor); descriptor.checkError() }
    override fun closeSuccessfully() {
        if (closed) return
        descriptor.checkError()
        descriptor.close()
        closed = true
    }
    override fun abort() {
        if (closed) return
        try { descriptor.closeWithError("BMD did not finish this save") } finally { closed = true }
    }
}

private class StaleOwner : IOException()
private class SaveStopped : IOException()
private class ByteMismatch : IOException()
private class IdentityUncertain(message: String) : IOException(message)
private class SaveFailure(message: String) : IOException(message)
private class LocalIntegrityFailure(message: String) : IOException(message)
private suspend fun checkRunning(stop: () -> Boolean) { currentCoroutineContext().ensureActive(); if (stop()) throw SaveStopped() }
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
