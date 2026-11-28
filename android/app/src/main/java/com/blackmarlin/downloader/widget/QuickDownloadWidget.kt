package com.blackmarlin.downloader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.blackmarlin.downloader.R
import com.blackmarlin.downloader.bmdGraph
import com.blackmarlin.downloader.ui.BmdUiStore
import com.blackmarlin.downloader.ui.TransferStage
import com.blackmarlin.downloader.ui.UiTask
import com.blackmarlin.downloader.ui.isActive
import com.blackmarlin.downloader.ui.label
import com.blackmarlin.downloader.ui.progressFraction
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QuickDownloadWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        context.bmdGraph().quickDownloads.restore(ids)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) context.bmdGraph().quickDownloads.untrack(id)
    }
}

internal val UiTask.showsWidgetProgress: Boolean
    get() = stage.isActive || stage == TransferStage.WAITING_NETWORK

private const val DOWNLOAD_SHARE = .95f

internal val UiTask.widgetProgress: Float?
    get() = when (stage) {
        TransferStage.VERIFYING -> DOWNLOAD_SHARE
        TransferStage.SAVING -> DOWNLOAD_SHARE + .04f * (progressFraction ?: 0f)
        TransferStage.COMPLETE -> 1f
        else -> progressFraction?.times(DOWNLOAD_SHARE)
    }

// Only task IDs are stored here. The repository remains the source of transfer state.
class QuickDownloadWidgets(private val context: Context, private val store: BmdUiStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager = AppWidgetManager.getInstance(context)
    private val prefs = context.getSharedPreferences("quick-download-widgets", Context.MODE_PRIVATE)
    private val mutableIds = MutableStateFlow(emptyMap<Int, String>())
    val taskIds = mutableIds.asStateFlow()
    private val observers = mutableMapOf<Int, Job>()
    private val faces = mutableMapOf<Int, Face>()

    init {
        val provider = ComponentName(context, QuickDownloadWidget::class.java)
        restore(manager.getAppWidgetIds(provider))
    }

    fun restore(ids: IntArray) {
        for (id in ids) {
            val taskId = prefs.getString(id.toString(), null)
            if (taskId != null && id !in observers) observe(id, taskId, restored = true)
            else render(id, faces[id] ?: Face.Idle)
        }
    }

    fun track(id: Int, taskId: String) {
        if (manager.getAppWidgetInfo(id) == null) return
        prefs.edit().putString(id.toString(), taskId).apply()
        observe(id, taskId, restored = false)
    }

    fun untrack(id: Int) {
        prefs.edit().remove(id.toString()).apply()
        mutableIds.value = mutableIds.value - id
        observers.remove(id)?.cancel()
        faces.remove(id)
    }

    private fun observe(id: Int, taskId: String, restored: Boolean) {
        observers.remove(id)?.cancel()
        mutableIds.value = mutableIds.value + (id to taskId)
        observers[id] = scope.launch {
            var seen = restored
            store.state.collect { state ->
                if (state.loading) return@collect
                val task = state.tasks.find { it.id == taskId }
                if (task == null && !seen) return@collect
                seen = true
                val face = when {
                    task == null -> Face.Idle
                    task.stage == TransferStage.COMPLETE -> Face.Success
                    task.showsWidgetProgress -> Face.Progress(task.widgetProgress?.times(100)?.roundToInt(), task.stage.label)
                    else -> Face.Idle
                }
                if (faces.put(id, face) != face) render(id, face)
                if (face == Face.Success) delay(2_200)
                if (face == Face.Success || task == null) {
                    render(id, Face.Idle)
                    untrack(id)
                }
            }
        }
        val preparing = Face.Progress(null, context.getString(R.string.quick_download_preparing))
        faces[id] = preparing
        render(id, preparing)
    }

    private fun render(id: Int, face: Face) {
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, QuickDownloadActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Its own request code: PendingIntents that differ only in extras would otherwise be one and the same.
        val openAndPaste = PendingIntent.getActivity(
            context, -id,
            Intent(context, QuickDownloadActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(QuickDownloadActivity.EXTRA_AUTOFILL, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.quick_download_widget).apply {
            setViewVisibility(R.id.quick_download_form, if (face == Face.Idle) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.quick_download_progress, if (face is Face.Progress) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.quick_download_success, if (face == Face.Success) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.quick_download_url, openAndPaste)
            setOnClickPendingIntent(R.id.quick_download_button, open)
            setOnClickPendingIntent(R.id.quick_download_progress, open)
            if (face is Face.Progress) {
                setProgressBar(R.id.quick_download_progress, 100, face.percent ?: 0, face.percent == null)
                setContentDescription(R.id.quick_download_progress, face.label)
            }
        })
    }

    private sealed interface Face {
        data object Idle : Face
        data object Success : Face
        data class Progress(val percent: Int?, val label: String) : Face
    }
}
