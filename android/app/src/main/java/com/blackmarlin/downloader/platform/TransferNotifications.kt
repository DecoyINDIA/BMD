package com.blackmarlin.downloader.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blackmarlin.downloader.MainActivity
import com.blackmarlin.downloader.R
import com.blackmarlin.downloader.data.TaskEntity

class TransferNotifications(private val context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    companion object { const val TRANSFERS = "transfers"; const val COMPLETED = "completed"; const val LOCAL_SERVICE_ID = 900001 }
    init {
        manager.createNotificationChannel(NotificationChannel(TRANSFERS, "Transfers", NotificationManager.IMPORTANCE_LOW).apply { description = "Active downloads and local saves"; setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(COMPLETED, "Saved files and problems", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Completed files and downloads that need attention" })
    }
    val permissionGranted get() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun channelEnabled(id: String) = manager.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
    private fun open(taskId: String?): PendingIntent = PendingIntent.getActivity(context, taskId?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("bmd://transfer/${taskId.orEmpty()}"))
            .putExtra("task_id", taskId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(task: TaskEntity, verb: String): PendingIntent = PendingIntent.getBroadcast(context, (task.id + verb + task.runGeneration).hashCode(),
        Intent(context, TransferActionReceiver::class.java).setData(android.net.Uri.parse("bmd-action://${task.id}/${task.scheduleEpoch}/${task.runGeneration}/$verb"))
            .setAction(verb).putExtra("task_id", task.id).putExtra("generation", task.runGeneration).putExtra("schedule_epoch", task.scheduleEpoch), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun active(task: TaskEntity? = null, privateNames: Boolean = true, local: Boolean = false): Notification {
        val title = if (privateNames || task == null) if (local) "Saving your download" else "Download in progress" else task.name
        val description = task?.detail ?: when (task?.stage) {
            "WAITING_NETWORK" -> "Waiting for an allowed network"
            "VERIFYING" -> "Checking the downloaded file"
            "SAVING" -> "Saving to the selected destination"
            "PAUSING", "CANCELING" -> "Stopping safely"
            else -> "Preparing your download"
        }
        val builder = NotificationCompat.Builder(context, TRANSFERS).setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(title).setContentText(description).setContentIntent(open(task?.id)).setOnlyAlertOnce(true)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPublicVersion(NotificationCompat.Builder(context, TRANSFERS).setSmallIcon(R.drawable.ic_download_notification).setContentTitle("Black Marlin transfer").build())
        if (task != null) {
            val total = task.totalBytes
            val current = if (local) task.saveBytes else task.receivedBytes
            if (total != null && total > 0) builder.setProgress(1000, ((current.toDouble() / total).coerceIn(0.0, 1.0) * 1000).toInt(), false)
            else builder.setProgress(0, 0, true)
            if (!local && task.userIntent == "RUN") builder.addAction(0, "Pause", action(task, "PAUSE"))
            builder.addAction(0, "Cancel", action(task, "CANCEL"))
        }
        return builder.build()
    }
    fun update(task: TaskEntity, privateNames: Boolean, local: Boolean = false, serviceOwned: Boolean = false) {
        if (permissionGranted) runCatching { manager.notify(if (serviceOwned) LOCAL_SERVICE_ID else task.jobId, active(task, privateNames, local)) }
    }
    fun completed(task: TaskEntity, privateNames: Boolean) {
        manager.cancel(task.jobId)
        if (!permissionGranted) return
        val title = if (task.stage == "COMPLETE") "File saved" else "Download needs attention"
        val content = if (privateNames) task.detail ?: "Open Black Marlin to view the file." else task.name
        runCatching { manager.notify(task.jobId + 1000000, NotificationCompat.Builder(context, COMPLETED)
            .setSmallIcon(R.drawable.ic_download_notification).setContentTitle(title).setContentText(content)
            .setContentIntent(open(task.id)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()) }
    }
}
