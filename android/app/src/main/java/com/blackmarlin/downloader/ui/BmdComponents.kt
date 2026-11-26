package com.blackmarlin.downloader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blackmarlin.downloader.R
import kotlin.math.roundToInt

@Composable
fun ScreenBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
fun PageIntro(title: String, description: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SectionTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

@Composable
fun ContentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
fun BrandEmblem(modifier: Modifier = Modifier, large: Boolean = false) {
    val teal = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.primaryContainer
    Box(modifier.size(if (large) 112.dp else 56.dp).clip(RoundedCornerShape(if (large) 32.dp else 18.dp))
        .background(soft), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val wave = Path().apply {
                moveTo(0f, size.height * .72f)
                cubicTo(size.width * .30f, size.height * .40f, size.width * .58f, size.height * .92f, size.width, size.height * .52f)
                lineTo(size.width, size.height); lineTo(0f, size.height); close()
            }
            drawPath(wave, teal.copy(alpha = .12f))
        }
        Icon(Icons.Outlined.Download, contentDescription = null, tint = teal, modifier = Modifier.size(if (large) 52.dp else 28.dp))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    brand: Boolean = false,
) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (brand) BrandEmblem(large = true) else Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(description, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) Button(onAction, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(actionLabel) }
            if (secondaryLabel != null && onSecondary != null) TextButton(onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun InfoBanner(title: String, body: String, warning: Boolean = false, action: String? = null, onAction: (() -> Unit)? = null) {
    val color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val foreground = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = color, contentColor = foreground, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (action != null && onAction != null) TextButton(onAction) { Text(action) }
            }
        }
    }
}

@Composable
fun InlineError(message: String?) {
    if (message != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, description: String? = null, onClick: () -> Unit, trailing: String? = null) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
fun ChoiceRow(title: String, description: String? = null, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun TaskStatus(task: UiTask) {
    val color = when {
        task.stage.needsAttention -> MaterialTheme.colorScheme.error
        task.stage == TransferStage.COMPLETE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (task.stage.needsAttention || task.stage == TransferStage.COMPLETE) Icon(
            if (task.stage == TransferStage.COMPLETE) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            null, Modifier.size(15.dp), tint = color)
        Text(if (task.savedBeforeCancellation) "Saved before cancellation" else task.stage.label,
            style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun ProgressSummary(task: UiTask, reducedMotion: Boolean = false, compact: Boolean = false) {
    val fraction = task.progressFraction
    val showProgress = task.stage != TransferStage.CANCELED && task.stage != TransferStage.CANCELING
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) {
        stateDescription = task.progressDescription()
    }) {
        if (showProgress) {
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
            else if (task.stage.isActive && !reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
            else Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val received = if (task.stage == TransferStage.SAVING) task.saveBytes else task.bytesReceived
            val total = if (task.stage == TransferStage.SAVING) task.saveTotalBytes else task.totalBytes
            Text(buildString {
                append(formatBytes(received))
                total?.let { append(" / ${formatBytes(it)}") }
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (task.stage == TransferStage.DOWNLOADING && task.bytesPerSecond != null && task.bytesPerSecond > 0) {
                Text("${formatBytes(task.bytesPerSecond)}/s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (fraction != null) Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!compact && task.stage == TransferStage.DOWNLOADING) formatDuration(task.secondsRemaining)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferRow(task: UiTask, selected: Boolean, selecting: Boolean, reducedMotion: Boolean, onClick: () -> Unit, onSelect: () -> Unit, onAction: (TaskOperation) -> Unit) {
    Surface(Modifier.fillMaxWidth().combinedClickable(onClick = if (selecting) onSelect else onClick, onLongClick = onSelect),
        shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selecting) Checkbox(selected, onCheckedChange = { onSelect() }, modifier = Modifier.semantics { contentDescription = "Select ${task.name}" })
                else FileTypeIcon(task.mimeType)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(task.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!selecting && TaskOperation.PAUSE in task.permittedActions) IconButton({ onAction(TaskOperation.PAUSE) }) {
                    Icon(Icons.Outlined.Pause, stringResource(R.string.content_pause_file, task.name, task.progressDescription()))
                } else if (!selecting && TaskOperation.RESUME in task.permittedActions) IconButton({ onAction(TaskOperation.RESUME) }) {
                    Icon(Icons.Outlined.PlayArrow, stringResource(R.string.content_resume_file, task.name))
                }
            }
            TaskStatus(task)
            ProgressSummary(task, reducedMotion, compact = true)
        }
    }
}

@Composable
fun FileTypeIcon(mimeType: String?, modifier: Modifier = Modifier) {
    val icon = when {
        mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
        mimeType?.startsWith("video/") == true -> Icons.Outlined.VideoFile
        mimeType?.startsWith("audio/") == true -> Icons.Outlined.AudioFile
        mimeType?.contains("zip") == true || mimeType?.contains("compressed") == true -> Icons.Outlined.Archive
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    Box(modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(file: UiFile, selected: Boolean = false, selecting: Boolean = false, onClick: () -> Unit, onSelect: () -> Unit) {
    Surface(Modifier.fillMaxWidth().combinedClickable(onClick = if (selecting) onSelect else onClick, onLongClick = onSelect),
        shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(selected, onCheckedChange = { onSelect() }, modifier = Modifier.semantics { contentDescription = "Select ${file.name}" })
            else FileTypeIcon(file.mimeType)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatBytes(file.bytes)} · ${file.destinationName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!file.accessible) Text("File unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            if (!selecting) Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DestinationRow(destination: UiDestination, onClick: () -> Unit) {
    SettingsRow(Icons.Outlined.FolderOpen, destination.displayName,
        if (destination.available) destination.freeBytes?.let { "${formatBytes(it)} available" } ?: "Space checked again when saving" else "Access is no longer available",
        onClick)
}
