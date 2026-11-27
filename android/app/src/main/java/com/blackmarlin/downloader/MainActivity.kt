package com.blackmarlin.downloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blackmarlin.downloader.ui.BmdApp
import com.blackmarlin.downloader.ui.BmdEffect
import com.blackmarlin.downloader.ui.BmdScreen
import com.blackmarlin.downloader.ui.BmdUiStoreOwner
import com.blackmarlin.downloader.ui.BmdViewModel
import com.blackmarlin.downloader.ui.DestinationKind
import com.blackmarlin.downloader.ui.UiDestination
import com.blackmarlin.downloader.ui.UiFile
import com.blackmarlin.downloader.ui.boundSharedText
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class MainActivity : ComponentActivity() {
    private val model: BmdViewModel by viewModels {
        viewModelFactory {
            initializer { BmdViewModel((application as BmdUiStoreOwner).uiStore, createSavedStateHandle()) }
        }
    }
    private var destinationTarget: String? = null
    private var destinationDraftId: String? = null
    private var locateTaskId: String? = null
    private data class PendingDocumentResult(val target: String, val uri: String, val flags: Int, val locate: Boolean, val draftId: String? = null)
    private var pendingDocumentResult: PendingDocumentResult? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.refreshSystemState()
        if (!granted) model.message("Notifications are off. You can still see transfer status in the app.")
    }
    private val destinationPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = destinationTarget
        val draftId = destinationDraftId
        destinationTarget = null
        destinationDraftId = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && target != null && uri != null) {
            pendingDocumentResult = PendingDocumentResult(target, uri.toString(), result.data?.flags ?: 0, locate = false, draftId)
            deliverPendingDocumentResult()
        }
    }
    private val locatePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val taskId = locateTaskId
        locateTaskId = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && taskId != null && uri != null) {
            pendingDocumentResult = PendingDocumentResult(taskId, uri.toString(), result.data?.flags ?: 0, locate = true)
            deliverPendingDocumentResult()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destinationTarget = savedInstanceState?.getString("destinationTarget")
        destinationDraftId = savedInstanceState?.getString("destinationDraftId")
        locateTaskId = savedInstanceState?.getString("locateTaskId")
        savedInstanceState?.getString("documentResultUri")?.let { uri ->
            val target = savedInstanceState.getString("documentResultTarget")
            if (target != null) pendingDocumentResult = PendingDocumentResult(target, uri, savedInstanceState.getInt("documentResultFlags"),
                savedInstanceState.getBoolean("documentResultLocate"), savedInstanceState.getString("documentResultDraftId"))
        }
        setContent { BmdApp(model, ::handlePlatformEffect) }
        if (savedInstanceState == null) handleEntryIntent(intent)
        if (pendingDocumentResult != null) deliverPendingDocumentResult()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEntryIntent(intent)
    }

    override fun onResume() { super.onResume(); model.refreshSystemState() }
    override fun onSaveInstanceState(outState: Bundle) {
        destinationTarget?.let { outState.putString("destinationTarget", it) }
        destinationDraftId?.let { outState.putString("destinationDraftId", it) }
        locateTaskId?.let { outState.putString("locateTaskId", it) }
        pendingDocumentResult?.let {
            outState.putString("documentResultTarget", it.target); outState.putString("documentResultUri", it.uri)
            outState.putInt("documentResultFlags", it.flags); outState.putBoolean("documentResultLocate", it.locate)
            outState.putString("documentResultDraftId", it.draftId)
        }
        super.onSaveInstanceState(outState)
    }

    private fun handleEntryIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> boundSharedText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))?.let(model::incomingShare)
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri?.scheme.equals("https", true) || uri?.scheme.equals("http", true)) model.incomingShare(uri.toString())
                else intent.getStringExtra("task_id")?.let { model.navigate(BmdScreen.TRANSFER_DETAIL, it) }
            }
            else -> intent.getStringExtra("task_id")?.let { model.navigate(BmdScreen.TRANSFER_DETAIL, it) }
        }
    }

    private fun handlePlatformEffect(effect: BmdEffect) {
        when (effect) {
            is BmdEffect.Message -> Unit // Snackbar is owned by the Compose root.
            BmdEffect.ReadClipboard -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // Paste is explicit and reads text only; never dereference an arbitrary clipboard URI.
                val text = boundSharedText(clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
                model.pastedText(text)
            }
            is BmdEffect.PickDestination -> {
                destinationTarget = effect.target
                destinationDraftId = effect.draftSubmissionId
                launchSafely {
                    destinationPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                }
            }
            is BmdEffect.LocateSavedFile -> {
                locateTaskId = effect.taskId
                launchSafely {
                    locatePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                }
            }
            BmdEffect.RequestNotificationPermission -> if (Build.VERSION.SDK_INT >= 33)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else model.refreshSystemState()
            is BmdEffect.OpenNotificationSettings -> launchSafely {
                val settingsIntent = if (effect.channelId != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, effect.channelId) else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                startActivity(settingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            is BmdEffect.OpenFile -> fileIntent(effect.file, share = false)
            is BmdEffect.ShareFile -> fileIntent(effect.file, share = true)
            is BmdEffect.ShareReport -> launchSafely {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, effect.text).putExtra(Intent.EXTRA_SUBJECT, "Black Marlin diagnostic report"), "Share diagnostic report"))
            }
            is BmdEffect.OpenExternalLink -> launchSafely {
                val uri = Uri.parse(effect.url)
                if (uri.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            is BmdEffect.UpdateSystemBars -> enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { effect.darkTheme },
                navigationBarStyle = SystemBarStyle.auto(Color.rgb(247, 248, 250), Color.rgb(8, 27, 32)) { effect.darkTheme },
            )
            is BmdEffect.ProtectCredentialWindow -> if (effect.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun deliverPendingDocumentResult() {
        val result = pendingDocumentResult ?: return
        lifecycleScope.launch {
            lifecycle.withResumed { }
            // ActivityResultRegistry can dispatch at STARTED. Yield past the resume callbacks
            // before any command that requires visible admission or starts a local dataSync FGS.
            yield()
            if (pendingDocumentResult !== result) return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                deliverPendingDocumentResult(); return@launch
            }
            pendingDocumentResult = null
            if (result.locate) model.savedFileLocated(result.target, result.uri, result.flags)
            else {
                val uri = Uri.parse(result.uri)
                val name = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Selected folder" } }
                    .getOrDefault("Selected folder")
                model.destinationChosen(result.target, UiDestination(DestinationKind.DOCUMENT_TREE, name, result.uri), result.flags, result.draftId)
            }
        }
    }

    private fun fileIntent(file: UiFile, share: Boolean) {
        val uri = Uri.parse(file.contentUri)
        if (uri.scheme != "content") { model.message("This file does not have an accessible Android document address."); return }
        launchSafely {
            val target = if (share) Intent(Intent.ACTION_SEND).setType(file.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType)
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            target.clipData = ClipData.newUri(contentResolver, file.name, uri)
            startActivity(Intent.createChooser(target, if (share) "Share file" else "Open file"))
        }
    }

    private fun launchSafely(action: () -> Unit) {
        try { action()
        } catch (_: ActivityNotFoundException) { model.message("No installed app can handle this action.")
        } catch (_: SecurityException) { model.message("Access to this file or location is no longer available. Choose it again to grant access."); model.refreshSystemState()
        } catch (_: IllegalArgumentException) { model.message("This file or location could not be opened.") }
    }
}
