package com.blackmarlin.downloader.platform

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import androidx.work.*
import com.blackmarlin.downloader.BmdGraph
import com.blackmarlin.downloader.data.TaskEntity
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Android owns execution windows; scheduled callbacks still re-check Room intent and epoch. */
class TaskScheduler(private val graph: BmdGraph) {
    private val context = graph.context
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val work = WorkManager.getInstance(context)

    suspend fun schedule(task: TaskEntity, fromVisibleAction: Boolean) {
        graph.ensureReady()
        graph.gate.withLock {
        val current = graph.db.tasks().task(task.id)
        if (current?.scheduleEpoch != task.scheduleEpoch || current.userIntent != "RUN" || current.userResumeRequired || current.stage == "COMPLETE") return@withLock
        if (task.verified || task.stage in BmdGraph.LOCAL_STAGES || task.recoveryCandidateUri != null) {
            scheduleLocalLocked(current, fromVisibleAction)
            return@withLock
        }
        if (Build.VERSION.SDK_INT >= 34) {
            if (!fromVisibleAction || !graph.visibility.visible) {
                holdLocked(task, "WAITING_ANDROID", "Open Black Marlin and tap Resume to start this transfer.")
                return@withLock
            }
            try {
                val extras = PersistableBundle().apply { putString(TASK, task.id); putLong(EPOCH, task.scheduleEpoch) }
                val job = JobInfo.Builder(task.jobId, ComponentName(context, TransferJobService::class.java))
                    .setUserInitiated(true).setExtras(extras)
                    .setRequiredNetworkType(if (task.networkPolicy == "UNMETERED" && !task.allowMeteredOnce) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(task.totalBytes?.let { (it - task.checkpointBytes).coerceAtLeast(0) } ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                    .build()
                val pending = jobs.getPendingJob(task.jobId)
                if (pending?.extras?.getString(TASK) == task.id && pending.extras.getLong(EPOCH) == task.scheduleEpoch) return@withLock
                if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS) holdLocked(task, "WAITING_ANDROID", "Android could not schedule this transfer. Tap Resume to try again.")
            } catch (_: RuntimeException) {
                holdLocked(task, "WAITING_ANDROID", "Android has not allowed this transfer to start. Return to the app and tap Resume.")
            }
        } else {
            val constraints = Constraints.Builder().setRequiredNetworkType(if (task.networkPolicy == "UNMETERED" && !task.allowMeteredOnce) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(data(task)).setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("bmd-transfer").build()
            work.enqueueUniqueWork(networkName(task), ExistingWorkPolicy.KEEP, request)
        }
        }
    }

    suspend fun scheduleLocal(task: TaskEntity, fromVisibleAction: Boolean) {
        graph.gate.withLock {
            val current = graph.db.tasks().task(task.id) ?: return@withLock
            if (current.scheduleEpoch != task.scheduleEpoch) return@withLock
            scheduleLocalLocked(current, fromVisibleAction)
        }
    }
    private suspend fun scheduleLocalLocked(task: TaskEntity, fromVisibleAction: Boolean) {
        if (task.userIntent != "RUN" || task.userResumeRequired || task.stage == "COMPLETE") return
        val bytes = task.totalBytes
        // This is an application budget, not an Android guarantee. Providers can be remote/slow.
        if (bytes != null && bytes <= SMALL_LOCAL_BYTES && task.destinationKind == "PUBLIC_DOWNLOADS" && task.recoveryCandidateUri == null) {
            work.enqueueUniqueWork(localName(task), ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalSaveWorker>().setInputData(data(task)).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).addTag("bmd-local-save").build())
        } else if (fromVisibleAction && graph.visibility.visible) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocalSaveService::class.java)
                    .putExtra(TASK, task.id).putExtra(EPOCH, task.scheduleEpoch))
            } catch (_: RuntimeException) {
                holdLocked(task, "WAITING_SAVE", "Android could not start saving. Downloaded data is retained; retry while the app is open.")
            }
        } else {
            holdLocked(task, "WAITING_SAVE", "Downloaded data is ready. Open this transfer and tap Retry save to verify and save it.")
        }
    }

    /** Closing the durable epoch precedes canceling OS work; no callback may reopen admission. */
    suspend fun cancel(task: TaskEntity) = graph.gate.withLock { cancelLocked(task) }
    fun cancelLocked(task: TaskEntity) {
        // A late command may cancel only its own OS epoch, never a newer accepted run.
        val pending = jobs.getPendingJob(task.jobId)
        if (pending?.extras?.getString(TASK) == task.id && pending.extras.getLong(EPOCH) == task.scheduleEpoch) jobs.cancel(task.jobId)
        work.cancelUniqueWork(networkName(task))
        work.cancelUniqueWork(localName(task))
    }
    fun enqueueRecovery() {
        work.enqueueUniqueWork("bmd-reconcile", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RecoveryWorker>().build())
    }
    private suspend fun holdLocked(old: TaskEntity, stage: String, detail: String) {
        val task = graph.db.tasks().task(old.id) ?: return
        if (task.scheduleEpoch == old.scheduleEpoch && task.userIntent == "RUN" && task.ownerSession == null && task.stage != "COMPLETE") {
            graph.db.tasks().update(task.copy(stage = stage, detail = detail, updatedAt = System.currentTimeMillis()))
        }
    }
    companion object {
        const val FIRST_TRANSFER_JOB_ID = 10_000
        const val LAST_TRANSFER_JOB_ID = 899_000
        const val FIRST_WORK_JOB_ID = 1_000_000
        const val LAST_WORK_JOB_ID = 1_999_999
        const val TASK = "task_id"
        const val EPOCH = "schedule_epoch"
        const val SMALL_LOCAL_BYTES = 64L * 1024 * 1024
        const val LOCAL_BUDGET_MS = 120_000L
        fun networkName(task: TaskEntity) = "bmd-network-${task.id}-${task.scheduleEpoch}"
        fun localName(task: TaskEntity) = "bmd-local-${task.id}-${task.scheduleEpoch}"
        fun data(task: TaskEntity) = workDataOf(TASK to task.id, EPOCH to task.scheduleEpoch)
    }
}
