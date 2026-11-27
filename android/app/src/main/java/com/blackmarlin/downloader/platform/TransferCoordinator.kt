package com.blackmarlin.downloader.platform

import android.app.ForegroundServiceStartNotAllowedException
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.os.StatFs
import android.os.Environment
import android.system.Os
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import com.blackmarlin.downloader.BmdGraph
import com.blackmarlin.downloader.bridge.NativeFailure
import com.blackmarlin.downloader.bridge.NativeSnapshot
import com.blackmarlin.downloader.data.TaskEntity
import com.blackmarlin.downloader.domain.DownloadPolicy
import com.blackmarlin.downloader.domain.StorageBudget
import com.blackmarlin.downloader.storage.deletePrivateTransferDirectory
import com.blackmarlin.downloader.ui.NetworkPolicy
import com.blackmarlin.downloader.ui.BmdUiException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class ExecutionOutcome(val retry: Boolean = false, val localReady: Boolean = false)

/** Room owns intent/generation. A native writer claim lives until Rust explicitly joins it. */
class TransferCoordinator internal constructor(
    private val graph: BmdGraph,
    private val networks: NetworkGateway,
    private val availableStorageBytes: (File) -> Long,
) {
    constructor(graph: BmdGraph) : this(
        graph,
        graph.networks,
        // `kotlin.` so it isn't the `this.runCatching` extension: there is no `this` yet here.
        { dir -> kotlin.runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0) },
    )

    private class ForegroundStartBlockedException(cause: Throwable) : Exception(cause)

    private val dao = graph.db.tasks()
    private data class Run(
        val task: TaskEntity,
        val local: Boolean,
        val connections: Int,
        val lease: NetworkLease?,
        val executorStopped: () -> Boolean = { false },
        val serviceOwnedNotification: Boolean = false,
        val stop: AtomicBoolean = AtomicBoolean(false),
        val native: AtomicLong = AtomicLong(0),
        val knownTotal: AtomicLong = AtomicLong(task.totalBytes ?: -1),
        var stopReason: String = "INTERRUPTED",
    )
    private val active = ConcurrentHashMap<String, Run>()

    /** Call while holding graph.gate after the durable intent has been written. */
    fun signalStop(taskId: String, reason: String = "INTERRUPTED") {
        active[taskId]?.let { run ->
            run.stopReason = reason
            run.stop.set(true)
            val handle = run.native.get()
            if (handle > 0) graph.scope.launch { runCatching { graph.engine.stop(handle) } }
        }
    }
    fun hasOwner(taskId: String) = active.containsKey(taskId)
    fun stopFromAndroid(taskId: String, epoch: Long, reason: String) {
        active[taskId]?.takeIf { it.task.scheduleEpoch == epoch }?.let {
            it.stopReason = reason
            it.stop.set(true)
            graph.scope.launch { if (it.native.get() > 0) runCatching { graph.engine.stop(it.native.get()) } }
        }
        graph.scope.launch {
            graph.gate.withLock {
                val task = dao.task(taskId) ?: return@withLock
                if (task.scheduleEpoch != epoch || DownloadPolicy.isFinal(task.stage)) return@withLock
                if (reason == "USER_STOP") dao.update(task.copy(userResumeRequired = true, detail = "Stopped in Android. Resume from Black Marlin when ready.", updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun runNetwork(taskId: String, epoch: Long, assigned: Network?, requireAssigned: Boolean,
                           executorStopped: () -> Boolean = { false },
                           foreground: suspend (TaskEntity) -> Unit): ExecutionOutcome {
        currentCoroutineContext().ensureActive()
        graph.ensureReady()
        val run = graph.gate.withLock { withContext(NonCancellable) {
            val task = dao.task(taskId) ?: return@withContext null
            if (executorStopped() || !admissible(task, epoch) || task.verified || task.stage in BmdGraph.LOCAL_STAGES) return@withContext null
            if (!hasNetworkSpace(task)) {
                dao.update(task.copy(stage = "WAITING_SPACE", errorCode = "STORAGE_RESERVE", detail = safeError("STORAGE_RESERVE"), updatedAt = System.currentTimeMillis()))
                return@withContext null
            }
            val policy = NetworkPolicy.valueOf(task.networkPolicy)
            val network = assigned ?: if (!requireAssigned) networks.select(policy, task.allowMeteredOnce) else null
            val lease = network?.let { networks.admit(it, policy, task.allowMeteredOnce) }
            if (lease == null) {
                dao.update(task.copy(stage = "WAITING_NETWORK", detail = "Waiting for an allowed network.", updatedAt = System.currentTimeMillis()))
                return@withContext null
            }
            try {
                val settings = graph.preferences.state.value
                val networkRuns = active.values.filter { !it.local }
                val metered = networks.metered(lease.network)
                val host = DownloadPolicy.parse(task.sourceUrl, task.httpConsent).host
                val hostUsed = networkRuns.filter { DownloadPolicy.parse(it.task.sourceUrl, it.task.httpConsent).host == host }.sumOf { it.connections }
                val globalFree = settings.globalSegmentLimit - networkRuns.sumOf { it.connections }
                val hostFree = settings.perHostLimit - hostUsed
                val count = minOf(if (metered) 1 else settings.segmentsPerTransfer, globalFree, hostFree)
                if (networkRuns.size >= (if (metered) 1 else settings.maxActiveTransfers) || count < 1 ||
                    networkRuns.any { it.lease?.let { l -> networks.metered(l.network) } == true }) {
                    dao.update(task.copy(stage = "QUEUED", detail = "Waiting for a transfer slot.", updatedAt = System.currentTimeMillis()))
                    return@withContext null
                }
                val claimed = task.copy(runGeneration = Math.addExact(task.runGeneration, 1), ownerSession = graph.session,
                    stage = "CONNECTING", errorCode = null, detail = "Connecting to the source.", speed = 0, updatedAt = System.currentTimeMillis())
                val claimedRun = Run(claimed, false, count, lease, executorStopped)
                dao.update(claimed)
                active[taskId] = claimedRun
                claimedRun
            } finally {
                if (active[taskId]?.lease !== lease) networks.release(lease)
            }
        } } ?: return ExecutionOutcome(retry = mayRetry(taskId, epoch))
        var result = ExecutionOutcome()
        try {
            try {
                foreground(run.task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isForegroundStartNotAllowed(error)) throw ForegroundStartBlockedException(error)
                throw error
            }
            val request = networkRequest(run.task, run.lease!!, run.connections)
            startUnderGate(run, request)
            val end = poll(run)
            graph.gate.withLock {
                val latest = owned(run) ?: return@withLock
                when {
                    end.phase == "network_complete" -> {
                        dao.update(latest.copy(stage = "WAITING_VERIFY", totalBytes = end.totalBytes ?: latest.totalBytes,
                            receivedBytes = end.receivedBytes, checkpointBytes = end.checkpointBytes, speed = 0,
                            detail = "Download received. Preparing local verification.", updatedAt = System.currentTimeMillis()))
                        result = ExecutionOutcome(localReady = latest.userIntent == "RUN" && !latest.userResumeRequired)
                    }
                    end.phase == "failed" -> failLocked(latest, end.errorCode ?: "TRANSFER_FAILED")
                    else -> settleStoppedLocked(latest, run)
                }
            }
        } catch (cancelled: CancellationException) {
            run.stop.set(true)
            throw cancelled
        } catch (error: Exception) {
            graph.gate.withLock { owned(run)?.let {
                when {
                    it.userIntent != "RUN" || run.stop.get() || run.executorStopped() -> settleStoppedLocked(it, run)
                    error is ForegroundStartBlockedException -> dao.update(it.copy(
                        stage = "WAITING_ANDROID",
                        speed = 0,
                        errorCode = null,
                        detail = "Android did not allow this transfer to start in the background. Open Black Marlin and tap Resume to continue.",
                        updatedAt = System.currentTimeMillis(),
                    ))
                    else -> failLocked(it, (error as? NativeFailure)?.code ?: if (error is BmdUiException && error.recovery == BmdUiException.Recovery.SOURCE_CREDENTIALS) "SOURCE_AUTH_REQUIRED" else "TRANSFER_INTERRUPTED")
                }
            } }
        } finally {
            finish(run)
        }
        if (!result.localReady) result = result.copy(retry = mayRetry(taskId, epoch))
        return result
    }

    suspend fun runLocal(taskId: String, epoch: Long, budgetMs: Long?, executorStopped: () -> Boolean = { false }, serviceOwnedNotification: Boolean = false, foreground: suspend (TaskEntity) -> Unit): ExecutionOutcome {
        currentCoroutineContext().ensureActive()
        graph.ensureReady()
        var waitingForSlot = false
        val run = graph.gate.withLock { withContext(NonCancellable) {
            val task = dao.task(taskId) ?: return@withContext null
            if (executorStopped() || !admissible(task, epoch)) return@withContext null
            if (!task.verified && task.stage !in BmdGraph.LOCAL_STAGES && dao.publication(taskId) == null &&
                !File(graph.directory(taskId), "network-complete.json").isFile) return@withContext null
            // Provider copy/verification uses a single local executor, limiting RAM and disk pressure.
            if (active.values.any { it.local }) {
                waitingForSlot = true
                dao.update(task.copy(stage = "WAITING_SAVE", detail = "Waiting for the current file to finish saving.", updatedAt = System.currentTimeMillis()))
                return@withContext null
            }
            val claimed = task.copy(runGeneration = Math.addExact(task.runGeneration, 1), ownerSession = graph.session,
                stage = if (task.verified) "SAVING" else "VERIFYING", errorCode = null, speed = 0,
                detail = if (task.verified) "Saving the retained download." else "Checking downloaded bytes.", updatedAt = System.currentTimeMillis())
            dao.update(claimed)
            Run(claimed, true, 0, null, executorStopped, serviceOwnedNotification).also { active[taskId] = it }
        } } ?: return ExecutionOutcome(retry = waitingForSlot)
        val deadline = budgetMs?.let { SystemClock.elapsedRealtime() + it }
        val stopped = {
            if (deadline != null && SystemClock.elapsedRealtime() >= deadline) { run.stopReason = "LOCAL_BUDGET"; run.stop.set(true) }
            if (executorStopped()) run.stop.set(true)
            run.stop.get()
        }
        try {
            foreground(run.task)
            if (!run.task.verified) {
                val request = JSONObject().put("command", "verify").put("taskId", taskId)
                    .put("generation", run.task.runGeneration).put("privateDirectory", graph.directory(taskId).absolutePath)
                    .put("expectedSourceUrl", run.task.sourceUrl)
                startUnderGate(run, request)
                val end = poll(run, stopped)
                if (end.phase != "verified") {
                    graph.gate.withLock {
                        owned(run)?.let { if (end.phase == "failed") failLocked(it, end.errorCode ?: "VERIFY_FAILED") else settleStoppedLocked(it, run) }
                    }
                    return ExecutionOutcome()
                }
                releaseNative(run)
                val verifiedLength = end.totalBytes ?: throw NativeFailure("VERIFICATION_PROOF_MISSING")
                graph.gate.withLock {
                    owned(run)?.let { latest -> dao.update(latest.copy(verified = true, sha256Verified = latest.expectedSha256 != null,
                        totalBytes = verifiedLength, stage = "SAVING", detail = "Saving to the selected destination.", updatedAt = System.currentTimeMillis())) }
                }
            }
            val candidate = dao.task(taskId)?.recoveryCandidateUri
            val progress: suspend (Long) -> Unit = {
                dao.task(taskId)?.let { task -> graph.notifications.update(task, graph.preferences.state.value.privateNotifications, true, serviceOwnedNotification) }
            }
            val outcome = if (candidate == null) graph.publication.save(taskId, run.task.runGeneration, graph.session, stopped, progress)
                else graph.publication.locate(taskId, run.task.runGeneration, graph.session, candidate, stopped, progress)
            if (outcome.saved) {
                // The provider URI and file index are committed before private data is removed.
                graph.gate.withLock {
                    owned(run)?.takeIf { it.stage == "COMPLETE" }?.let { saved ->
                        graph.credentials.removeTask(taskId)
                        cleanPrivateDirectory(taskId)
                        dao.publication(taskId)?.let { dao.putPublication(it.copy(phase = "CLEANED")) }
                        dao.update(saved.copy(sourceUrl = "", recoveryCandidateUri = null, updatedAt = System.currentTimeMillis()))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            run.stop.set(true)
            throw cancelled
        } catch (error: Exception) {
            graph.gate.withLock {
                owned(run)?.let { if (it.stage != "COMPLETE") failLocked(it, (error as? NativeFailure)?.code ?: "SAVE_RETRY", local = true) }
            }
        } finally { finish(run) }
        dao.task(taskId)?.let { graph.notifications.completed(it, graph.preferences.state.value.privateNotifications) }
        return ExecutionOutcome()
    }

    private fun admissible(task: TaskEntity, epoch: Long) = task.scheduleEpoch == epoch && task.userIntent == "RUN" &&
        !task.userResumeRequired && task.ownerSession == null && !DownloadPolicy.isFinal(task.stage) && !active.containsKey(task.id)
    private suspend fun owned(run: Run): TaskEntity? = dao.task(run.task.id)?.takeIf {
        it.runGeneration == run.task.runGeneration && it.ownerSession == graph.session
    }

    private suspend fun networkRequest(task: TaskEntity, lease: NetworkLease, count: Int): JSONObject {
        val source = DownloadPolicy.parse(task.sourceUrl, task.httpConsent)
        val credential = graph.credentials.forTask(task.id, source.origin)
        credential?.let { DownloadPolicy.validateCredential(source, it) }
        return JSONObject().put("command", "start").put("taskId", task.id).put("generation", task.runGeneration)
            .put("url", source.url).put("expectedOrigin", source.origin).put("credentialOrigin", credential?.origin)
            .put("authorization", graph.credentials.authorization(credential)).put("httpConsent", task.httpConsent)
            .put("privateDirectory", graph.directory(task.id).absolutePath).put("fileName", task.name)
            .put("resume", File(graph.directory(task.id), "payload.bmd.json").isFile).put("expectedSha256", task.expectedSha256)
            .put("network", JSONObject().put("handle", lease.handle).put("generation", lease.generation))
            .put("maxConnections", count).put("speedLimitBytesPerSecond", graph.preferences.state.value.speedLimitBytesPerSecond.takeIf { it > 0 })
    }

    private suspend fun startUnderGate(run: Run, request: JSONObject) = graph.gate.withLock {
        val task = owned(run) ?: throw NativeFailure("STALE_OWNER")
        if (run.stop.get() || run.executorStopped() || task.userIntent != "RUN" || task.userResumeRequired) throw NativeFailure("ADMISSION_CLOSED")
        run.lease?.let { if (!it.valid.get() || !networks.eligible(it.network, it.policy, it.allowMetered)) throw NativeFailure("NETWORK_UNAVAILABLE") }
        // JNI returns an executor handle immediately; no network work runs on the Android main thread.
        // withContext has prompt cancellation on its return: publish the returned handle in the
        // same non-cancelable section as admission so a real native writer can never be orphaned.
        withContext(NonCancellable) { run.native.set(graph.engine.command(request).getLong("handle")) }
    }

    private suspend fun poll(run: Run, stop: () -> Boolean = { run.stop.get() }): NativeSnapshot {
        var stopSent = false
        var stopAt: Long? = null
        var previousBytes: Long? = null
        var previousTime = SystemClock.elapsedRealtime()
        var lastWrite = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (run.executorStopped()) run.stop.set(true)
            val persisted = dao.task(run.task.id)
            if (persisted == null || persisted.runGeneration != run.task.runGeneration || persisted.ownerSession != graph.session || persisted.userIntent != "RUN" || persisted.userResumeRequired) run.stop.set(true)
            if (run.lease?.valid?.get() == false) { run.stopReason = "NETWORK_CHANGED"; run.stop.set(true) }
            if ((stop() || run.stop.get()) && !stopSent) { graph.engine.stop(run.native.get()); stopSent = true; stopAt = SystemClock.elapsedRealtime() }
            val snapshot = graph.engine.snapshot(run.native.get())
            snapshot.totalBytes?.let(run.knownTotal::set)
            val now = SystemClock.elapsedRealtime()
            if (snapshot.terminal && !snapshot.writerReleased || stopAt?.let { now - it >= 30_000 } == true) throw NativeFailure("WRITER_NOT_RELEASED")
            if (now - lastWrite >= 1000 || snapshot.terminal) {
                if (!run.local && !snapshot.terminal && !run.stop.get() && !hasNetworkSpace()) {
                    run.stopReason = "STORAGE_RESERVE"
                    run.stop.set(true)
                    graph.engine.stop(run.native.get())
                    stopSent = true; stopAt = now
                }
                val receiving = !run.local && snapshot.phase == "downloading"
                // The first downloading snapshot includes any resumed bytes. It establishes
                // the rate baseline; queued/probing and local verification have no such rate.
                val speed = if (receiving) previousBytes?.let { baseline ->
                    ((snapshot.receivedBytes - baseline).coerceAtLeast(0).toDouble() * 1000 /
                        (now - previousTime).coerceAtLeast(1)).toLong()
                } ?: 0 else 0
                graph.gate.withLock {
                    val task = owned(run)
                    if (task != null && task.userIntent == "RUN" && !snapshot.terminal) {
                        val stage = if (run.local) "VERIFYING" else if (receiving) "DOWNLOADING" else "CONNECTING"
                        val updated = task.copy(receivedBytes = if (receiving) snapshot.receivedBytes else task.receivedBytes,
                            checkpointBytes = if (receiving) snapshot.checkpointBytes else task.checkpointBytes,
                            totalBytes = snapshot.totalBytes ?: task.totalBytes, speed = if (run.local) 0 else speed,
                            stage = stage, detail = if (run.local) "Checking downloaded bytes." else if (receiving) "Downloading from ${task.origin}" else "Connecting to the source.", updatedAt = System.currentTimeMillis())
                        dao.update(updated)
                        graph.notifications.update(updated, graph.preferences.state.value.privateNotifications, run.local, run.serviceOwnedNotification)
                    }
                }
                previousBytes = snapshot.receivedBytes.takeIf { receiving }; previousTime = now; lastWrite = now
            }
            if (snapshot.terminal && snapshot.writerReleased) return snapshot
            delay(if (graph.visibility.visible) 250 else 1000)
        }
    }

    private suspend fun releaseNative(run: Run) {
        val handle = run.native.get()
        if (handle == 0L) return
        graph.engine.stop(handle)
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (true) {
            val end = graph.engine.snapshot(handle)
            if (end.terminal && end.writerReleased) break
            if (end.terminal || SystemClock.elapsedRealtime() >= deadline) throw NativeFailure("WRITER_NOT_RELEASED")
            delay(100)
        }
        graph.engine.release(handle)
        run.native.set(0)
    }

    private suspend fun finish(run: Run) = withContext(NonCancellable + Dispatchers.IO) {
        // Cancellation of a Coroutine/Job is not a native writer acknowledgement.
        // If a broken JNI implementation cannot acknowledge, retain ownership and data.
        val joined = runCatching { releaseNative(run) }.isSuccess
        if (!joined) {
            graph.gate.withLock { owned(run)?.let { dao.update(it.copy(stage = "INTERRUPTED", userResumeRequired = true,
                detail = "The engine has not confirmed that this transfer stopped. Open Android App info for Black Marlin, choose Force stop, then reopen it and retry. This interrupts all active transfers. Retained data will be checked when you retry.", errorCode = "WRITER_NOT_RELEASED")) } }
            return@withContext
        }
        run.lease?.let(networks::release)
        graph.gate.withLock {
            if (run.local && !run.serviceOwnedNotification) graph.notifications.manager.cancel(run.task.jobId)
            owned(run)?.let { task ->
                val settled = if (task.stage in setOf("CONNECTING", "DOWNLOADING", "VERIFYING", "SAVING", "PAUSING", "CANCELING")) settleStoppedLocked(task, run) else task
                var final = dao.task(settled.id) ?: settled
                if (final.userIntent == "CANCEL" && dao.publication(final.id) == null && final.stage != "COMPLETE") {
                    cleanPrivateDirectory(final.id)
                    graph.credentials.removeTask(final.id)
                    final = final.copy(stage = "CANCELED", sourceUrl = "", verified = false, detail = "Transfer canceled.")
                }
                dao.update(final.copy(ownerSession = null, speed = 0, updatedAt = System.currentTimeMillis()))
            }
            active.remove(run.task.id, run)
        }
    }

    private suspend fun settleStoppedLocked(task: TaskEntity, run: Run): TaskEntity {
        val local = run.local || task.verified || task.stage in BmdGraph.LOCAL_STAGES || dao.publication(task.id) != null
        val next = when {
            task.stage == "COMPLETE" -> task
            local -> task.copy(stage = "WAITING_SAVE", speed = 0, detail = "Downloaded data is retained. Retry saving when ready.")
            task.userIntent == "PAUSE" -> task.copy(stage = "PAUSED", speed = 0, detail = "Paused. The native checkpoint is retained.")
            task.userIntent == "CANCEL" -> task.copy(stage = "CANCELING", speed = 0, detail = "Stopping safely.")
            run.stopReason == "STORAGE_RESERVE" -> task.copy(stage = "WAITING_SPACE", speed = 0, errorCode = "STORAGE_RESERVE", detail = safeError("STORAGE_RESERVE"))
            run.stopReason == "NETWORK_CHANGED" -> task.copy(stage = "WAITING_NETWORK", speed = 0, detail = "Waiting for an allowed network.")
            else -> task.copy(stage = "INTERRUPTED", speed = 0, userResumeRequired = true, detail = "Android interrupted this transfer. Resume when ready.")
        }.copy(updatedAt = System.currentTimeMillis())
        dao.update(next)
        return next
    }

    private suspend fun failLocked(task: TaskEntity, code: String, local: Boolean = false) {
        val stage = when {
            code.endsWith("RESTART_REQUIRED") || code.contains("CHANGED") || code.contains("CHECKSUM") ||
                code in setOf("RANGE_RESUME_UNAVAILABLE", "CHECKPOINT_INVALID", "STAGING_ALREADY_EXISTS", "STAGING_LAYOUT_CONFLICT", "PROOF_INVALID", "PROOF_IDENTITY_MISMATCH", "VERIFICATION_MARKER_INVALID", "STAGED_FILE_MISSING") -> "FILE_CHANGED"
            local || task.verified || task.stage in BmdGraph.LOCAL_STAGES || code.startsWith("SAVE_") -> "WAITING_SAVE"
            code == "SOURCE_AUTH_REQUIRED" -> "NEEDS_CREDENTIALS"
            code in setOf("SOURCE_FORBIDDEN_OR_EXPIRED", "SOURCE_UNAVAILABLE", "SOURCE_PAGE_OR_EXPIRED") -> "NEEDS_LINK"
            code == "NETWORK_ALREADY_COMPLETE" -> "WAITING_VERIFY"
            code.contains("SPACE") || code == "STORAGE_FULL" -> "WAITING_SPACE"
            code.contains("NETWORK") -> "WAITING_NETWORK"
            else -> "FAILED"
        }
        dao.update(task.copy(stage = stage, speed = 0, errorCode = code, detail = safeError(code), updatedAt = System.currentTimeMillis()))
    }

    private suspend fun mayRetry(taskId: String, epoch: Long): Boolean = dao.task(taskId)?.let {
        it.scheduleEpoch == epoch && it.userIntent == "RUN" && !it.userResumeRequired && it.stage in setOf("QUEUED", "WAITING_NETWORK")
    } == true

    /** Reserve future copy space for active transfers and completed private files on primary storage. */
    private suspend fun hasNetworkSpace(candidate: TaskEntity? = null): Boolean {
        val available = availableStorageBytes(graph.context.noBackupFilesDir)
        var needed = StorageBudget.SAFETY_BYTES
        val networkRuns = active.values.filter { !it.local }
        for (run in networkRuns) needed = StorageBudget.sum(needed, StorageBudget.additionalBytes(
            run.knownTotal.get().takeIf { it >= 0 }, allocatedBytes(run.task), copiesOnPrivateVolume(run.task)))
        if (candidate != null && networkRuns.none { it.task.id == candidate.id }) needed = StorageBudget.sum(needed,
            StorageBudget.additionalBytes(candidate.totalBytes, allocatedBytes(candidate), copiesOnPrivateVolume(candidate)))
        for (task in dao.tasks()) if (task.id != candidate?.id && networkRuns.none { it.task.id == task.id } &&
            !DownloadPolicy.isFinal(task.stage) && (task.verified || task.stage in BmdGraph.LOCAL_STAGES) && copiesOnPrivateVolume(task)) {
            needed = StorageBudget.sum(needed, task.totalBytes ?: 0)
        }
        return available >= needed
    }
    private fun copiesOnPrivateVolume(task: TaskEntity) = task.destinationKind == "PUBLIC_DOWNLOADS" && Environment.isExternalStorageEmulated()
    private fun allocatedBytes(task: TaskEntity): Long {
        val directory = graph.directory(task.id)
        return listOf("payload.bmd.part", "verified.payload").fold(0L) { total, name ->
            val bytes = runCatching { Math.multiplyExact(Os.stat(File(directory, name).absolutePath).st_blocks, 512L) }.getOrDefault(0)
            StorageBudget.sum(total, bytes)
        }
    }

    private fun isForegroundStartNotAllowed(error: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        var current: Throwable? = error
        while (current != null) {
            // Android's own refusal, or a wrapper that says what it wraps. Any other
            // IllegalStateException is a fault, not Android's rule: it fails, never waits.
            if (Api31Foreground.isNotAllowed(current) ||
                (current is IllegalStateException &&
                    current.message?.contains("foreground", ignoreCase = true) == true)
            ) return true
            current = current.cause
        }
        return false
    }

    /** Caller holds gate and has proved no live writer / unresolved publication. */
    fun cleanPrivateDirectory(taskId: String) {
        deletePrivateTransferDirectory(graph.context.noBackupFilesDir, taskId)
    }

    @RequiresApi(31)
    private object Api31Foreground {
        fun isNotAllowed(error: Throwable) = error is ForegroundServiceStartNotAllowedException
    }

    companion object {
        fun safeError(code: String): String = when (code) {
            "SOURCE_AUTH_REQUIRED" -> "The source requires a username, password or token. Enter it for this HTTPS origin."
            "SOURCE_FORBIDDEN_OR_EXPIRED" -> "The source denied access or the link expired. Check the link before changing credentials."
            "SOURCE_UNAVAILABLE" -> "This file is no longer available at the source. Replace the link to start again."
            "SOURCE_PAGE_OR_EXPIRED" -> "The source returned a web page or an expired-link page. Use a fresh direct file link."
            "SOURCE_CHANGED_RESTART_REQUIRED" -> "The source changed. Restart explicitly to discard the old partial data."
            "NON_RESUMABLE_RESTART_REQUIRED", "RANGE_RESUME_UNAVAILABLE" -> "This source cannot safely resume the partial file. Confirm Restart to begin again."
            "CHECKSUM_MISMATCH", "STAGED_FILE_CHANGED" -> "The downloaded bytes do not match the expected file. Nothing was saved; confirm Restart or discard the private data."
            "STORAGE_FULL" -> "Private device storage is full. Free space, then resume; retained bytes have been kept."
            "STORAGE_RESERVE" -> "Free more device storage, then resume. Black Marlin keeps a 64 MiB safety margin and budgets for both the private download and its final copy in Downloads."
            "TLS_CERTIFICATE_REFUSED" -> "Android refused the source's HTTPS certificate. Check the link or contact the source; certificate verification stays enabled."
            "ENGINE_UNAVAILABLE" -> "The native download engine could not be loaded. Reinstall the complete app package."
            "NETWORK_UNAVAILABLE" -> "No allowed network is available. Your downloaded data is retained."
            "SAVE_RETRY" -> "Saving could not finish. The downloaded data is retained."
            "ADMISSION_CLOSED" -> "This transfer was stopped before a new request began."
            else -> "The transfer could not finish ($code). Downloaded data is retained for recovery."
        }
    }
}
