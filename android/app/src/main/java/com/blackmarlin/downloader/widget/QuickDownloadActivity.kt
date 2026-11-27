package com.blackmarlin.downloader.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackmarlin.downloader.R
import com.blackmarlin.downloader.bmdGraph
import com.blackmarlin.downloader.ui.TransferStage
import com.blackmarlin.downloader.ui.label
import kotlinx.coroutines.flow.first

class QuickDownloadActivity : ComponentActivity() {
    private val model by viewModels<QuickDownloadModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        if (provider?.className != QuickDownloadWidget::class.java.name) {
            finish()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.34f)
        if (Build.VERSION.SDK_INT >= 31) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply {
                blurBehindRadius = (18 * resources.displayMetrics.density).toInt()
            }
        }
        model.attach(id)
        setContent {
            val form = model.form.collectAsStateWithLifecycle().value
            val app = bmdGraph().repository.state.collectAsStateWithLifecycle().value
            val tracked = bmdGraph().quickDownloads.taskIds.collectAsStateWithLifecycle().value[id]
            val task = app.tasks.find { it.id == tracked }
            val face = when {
                form.busy -> PanelFace.PROGRESS
                form.consent != null -> PanelFace.FORM
                task?.stage == TransferStage.COMPLETE -> PanelFace.SUCCESS
                task?.showsWidgetProgress == true || (tracked != null && task == null) -> PanelFace.PROGRESS
                else -> PanelFace.FORM
            }
            val transferError = task?.takeIf {
                it.stage != TransferStage.COMPLETE && !it.showsWidgetProgress && !form.edited
            }?.let { it.detail ?: it.stage.label }
            LaunchedEffect(face) {
                if (face == PanelFace.SUCCESS) model.clearUrl()
            }
            val title = if (form.consent?.httpOrigin != null) R.string.quick_download_http_title else R.string.quick_download_metered_title
            MaterialTheme(colorScheme = darkColorScheme(primary = mint, surface = Color(0xFF222E43))) {
                DownloadPanel(
                    face = face,
                    url = form.url,
                    error = form.error ?: transferError,
                    progress = task?.widgetProgress,
                    progressLabel = task?.stage?.label ?: stringResource(R.string.quick_download_preparing),
                    reducedMotion = app.settings.reducedMotion,
                    focusOnOpen = savedInstanceState == null,
                    onUrlChange = model::edit,
                    onDownload = model::submit,
                    onDismiss = ::finish,
                )
                form.consent?.let { consent ->
                    AlertDialog(
                        onDismissRequest = model::decline,
                        title = { Text(stringResource(title)) },
                        text = {
                            Text(consent.httpOrigin?.let { stringResource(R.string.quick_download_http_message, it) }
                                ?: stringResource(R.string.quick_download_metered_message))
                        },
                        confirmButton = { TextButton(onClick = model::allow) { Text(stringResource(R.string.quick_download_allow)) } },
                        dismissButton = { TextButton(onClick = model::decline) { Text(stringResource(R.string.quick_download_cancel)) } },
                    )
                }
            }
        }
    }
}

private enum class PanelFace { FORM, PROGRESS, SUCCESS }

private val mint = Color(0xFFACF6E6)
private val ice = Color(0xFFEDF8FF)
private val mist = Color(0xFFCAD9EA)
private val glassShape = RoundedCornerShape(30.dp)
// Native adaptation of the liquid-glass skill's tint, bright upper rim and subtle prism fringe.
private val glassTint = Brush.linearGradient(
    listOf(Color(0xEE3A4663), Color(0xF21B263C), Color(0xED243447)),
)
private val glassRim = Brush.linearGradient(
    0f to Color(0xE2F2FAFF), .33f to Color(0xA98BCBDE), .68f to Color(0x726E76AA), 1f to Color(0xCA8FE4D7),
)

@Composable
private fun DownloadPanel(
    face: PanelFace,
    url: String,
    error: String?,
    progress: Float?,
    progressLabel: String,
    reducedMotion: Boolean,
    focusOnOpen: Boolean,
    onUrlChange: (String) -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val autoFocus = remember { mutableStateOf(focusOnOpen && face == PanelFace.FORM) }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val focusManager = LocalFocusManager.current
    fun download() {
        focusManager.clearFocus()
        keyboard?.hide()
        onDownload()
    }

    Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = face,
                transitionSpec = {
                    if (reducedMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (fadeIn(tween(220, delayMillis = 65)) + scaleIn(
                        spring(dampingRatio = .66f, stiffness = Spring.StiffnessLow),
                        initialScale = if (targetState == PanelFace.SUCCESS) .82f else .96f,
                    )) togetherWith (fadeOut(tween(125)) + scaleOut(tween(145), targetScale = .96f))
                },
                label = "Quick download",
            ) { shown ->
                Box(
                    modifier = Modifier.widthIn(max = 392.dp).fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures { } }
                        .shadow(18.dp, glassShape).clip(glassShape).background(glassTint)
                        .border(1.dp, glassRim, glassShape).padding(16.dp)
                        .heightIn(min = 62.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (shown) {
                        PanelFace.FORM -> {
                            LaunchedEffect(Unit) {
                                if (autoFocus.value && face == PanelFace.FORM) {
                                    autoFocus.value = false
                                    snapshotFlow { windowInfo.isWindowFocused }.first { it }
                                    withFrameNanos { }
                                    focus.requestFocus()
                                    withFrameNanos { }
                                    keyboard?.show()
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = url, onValueChange = onUrlChange,
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                    label = { Text(stringResource(R.string.quick_download_url)) },
                                    singleLine = true,
                                    isError = error != null,
                                    supportingText = error?.let { message ->
                                        { Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                                    },
                                    shape = RoundedCornerShape(17.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = ice, unfocusedTextColor = ice,
                                        focusedContainerColor = Color(0x67202C43), unfocusedContainerColor = Color(0x58202C43),
                                        focusedLabelColor = mint, unfocusedLabelColor = mist, unfocusedBorderColor = Color(0xFF8393AD),
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) download() }),
                                )
                                Button(
                                    onClick = ::download, enabled = url.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = Color(0xFF102E2C)),
                                ) { Text(stringResource(R.string.quick_download_action), fontWeight = FontWeight.Bold) }
                            }
                        }
                        PanelFace.PROGRESS -> {
                            val bar = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                .semantics { stateDescription = progressLabel }
                            val track = Color(0xFF4B6079)
                            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = bar, color = mint, trackColor = track)
                            else LinearProgressIndicator(modifier = bar, color = mint, trackColor = track)
                        }
                        PanelFace.SUCCESS -> Row(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(40.dp).background(Color(0x302AE9C8), CircleShape)
                                    .border(1.dp, glassRim, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(Modifier.size(19.dp)) {
                                    val joint = Offset(size.width * .42f, size.height * .68f)
                                    drawLine(mint, Offset(size.width * .19f, size.height * .46f), joint, 2.dp.toPx(), StrokeCap.Round)
                                    drawLine(mint, joint, Offset(size.width * .83f, size.height * .28f), 2.dp.toPx(), StrokeCap.Round)
                                }
                            }
                            Text(
                                stringResource(R.string.quick_download_success), color = ice,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
