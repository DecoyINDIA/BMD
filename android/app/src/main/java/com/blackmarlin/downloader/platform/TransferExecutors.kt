package com.blackmarlin.downloader.platform

import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.blackmarlin.downloader.bmdGraph
import com.blackmarlin.downloader.ui.BmdCommand
import com.blackmarlin.downloader.ui.TaskOperation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class TransferJobService : JobService() {
    private val stopped = ConcurrentHashMap<String, AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = bmdGraph()
        val id = params.extras.getString(TaskScheduler.TASK) ?: return false
        val epoch = params.extras.getLong(TaskScheduler.EPOCH)
        val stop = AtomicBoolean(false)
        // Android may reuse a numeric job ID after its old task history is removed.
        // A delayed callback must still identify the original durable task.
        val key = "$id:${params.jobId}:$epoch"
        stopped[key] = stop
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, params.jobId, graph.notifications.active(), JOB_END_NOTIFICATION_POLICY_REMOVE)
        graph.scope.launch {
            var outcome = ExecutionOutcome()
            try {
                outcome = graph.coordinator.runNetwork(id, epoch, if (Build.VERSION.SDK_INT >= 28) params.network else null, true, stop::get) { task ->
                    if (Build.VERSION.SDK_INT >= 34) setNotification(params, params.jobId, graph.notifications.active(task, graph.preferences.state.value.privateNotifications), JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
            } catch (_: Exception) {
                graph.coordinator.stopFromAndroid(id, epoch, "INTERRUPTED")
            } finally {
                if (!stop.get()) jobFinished(params, outcome.retry)
                stopped.remove(key, stop)
            }
            // The user-initiated network job has ended before local hash/provider work begins.
            if (outcome.localReady && !stop.get()) graph.db.tasks().task(id)?.let { graph.scheduler.scheduleLocal(it, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString(TaskScheduler.TASK) ?: return false
        stopped["$id:${params.jobId}:${params.extras.getLong(TaskScheduler.EPOCH)}"]?.set(true)
        val reason = if (Build.VERSION.SDK_INT >= 31 && params.stopReason == JobParameters.STOP_REASON_USER) "USER_STOP" else "NETWORK_CHANGED"
        bmdGraph().coordinator.stopFromAndroid(id, params.extras.getLong(TaskScheduler.EPOCH), reason)
        return reason != "USER_STOP"
    }
}

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.bmdGraph()
        val id = inputData.getString(TaskScheduler.TASK) ?: return Result.failure()
        val epoch = inputData.getLong(TaskScheduler.EPOCH, -1)
        return try {
            val outcome = graph.coordinator.runNetwork(id, epoch, null, false, { isStopped }) { task ->
                val notification = graph.notifications.active(task, graph.preferences.state.value.privateNotifications)
                setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(task.jobId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(task.jobId, notification))
            }
            if (outcome.localReady) graph.db.tasks().task(id)?.let { graph.scheduler.scheduleLocal(it, false) }
            if (outcome.retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
    }
}

class LocalSaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(TaskScheduler.TASK) ?: return Result.failure()
        val graph = applicationContext.bmdGraph()
        return try {
            val outcome = graph.coordinator.runLocal(id, inputData.getLong(TaskScheduler.EPOCH, -1), TaskScheduler.LOCAL_BUDGET_MS, { isStopped }) {}
            if (outcome.retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure() }
    }
}

/** Only a visible explicit user action starts a large/local-provider save service. */
class LocalSaveService : Service() {
    private val runs = ConcurrentHashMap<String, Pair<String, Long>>()
    private val closed = AtomicBoolean(false)
    private var latestStartId = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val graph = bmdGraph()
        val notification = graph.notifications.active(local = true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(TransferNotifications.LOCAL_SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(TransferNotifications.LOCAL_SERVICE_ID, notification)
        val id = intent?.getStringExtra(TaskScheduler.TASK)
        val epoch = intent?.getLongExtra(TaskScheduler.EPOCH, -1) ?: -1
        if (id == null || epoch < 0) { if (runs.isEmpty()) stopSelfResult(latestStartId); return START_NOT_STICKY }
        val key = "$id:$epoch"
        if (runs.putIfAbsent(key, id to epoch) == null) serviceScope.launch {
            try {
                do {
                    val outcome = graph.coordinator.runLocal(id, epoch, null, closed::get, true) { task -> graph.notifications.update(task, graph.preferences.state.value.privateNotifications, true, true) }
                    if (!outcome.retry || closed.get()) break
                    delay(1000)
                } while (isActive)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runs.remove(key)
                    if (runs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        closed.set(true)
        runs.values.forEach { (id, epoch) -> bmdGraph().coordinator.stopFromAndroid(id, epoch, "LOCAL_BUDGET") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        closed.set(true)
        runs.values.forEach { (id, epoch) -> bmdGraph().coordinator.stopFromAndroid(id, epoch, "INTERRUPTED") }
        serviceScope.cancel()
        super.onDestroy()
    }
}

class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("task_id") ?: return
        val generation = intent.getLongExtra("generation", -1)
        val epoch = intent.getLongExtra("schedule_epoch", -1)
        val operation = when (intent.action) { "PAUSE" -> TaskOperation.PAUSE; "CANCEL" -> TaskOperation.CANCEL; else -> return }
        if (generation < 0 || epoch < 0) return
        val pending = goAsync()
        context.bmdGraph().scope.launch {
            try { context.bmdGraph().repository.execute(BmdCommand.TaskAction(id, operation, expectedGeneration = generation, expectedScheduleEpoch = epoch)) }
            catch (_: Exception) { /* Stale notification actions never reopen admission. */ }
            finally { pending.finish() }
        }
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) context.bmdGraph().scheduler.enqueueRecovery()
    }
}
class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.bmdGraph().ensureReady()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
