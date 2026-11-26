package com.blackmarlin.downloader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blackmarlin.downloader.R

fun visibleTransfers(state: BmdAppState, session: BmdSession): List<UiTask> = state.tasks.filter {
    when (session.transferFilter) {
        TransferFilter.ALL -> true
        TransferFilter.ACTIVE -> it.stage.isActive
        TransferFilter.ATTENTION -> it.stage.needsAttention
    }
}.sortedByDescending { it.createdAt }

@Composable
fun TransfersScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    if (state.tasks.isEmpty()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Outlined.Download, stringResource(R.string.empty_transfers_title), stringResource(R.string.empty_transfers_body),
                actionLabel = stringResource(R.string.action_add_link), onAction = model::addLink,
                secondaryLabel = stringResource(R.string.action_how_it_works), onSecondary = { model.navigate(BmdScreen.HELP) }, brand = true)
        }
        return
    }
    val tasks = visibleTransfers(state, session)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("summary") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val active = state.tasks.count { it.stage.isActive }
                val attention = state.tasks.count { it.stage.needsAttention }
                Text(if (active > 0) pluralStringResource(R.plurals.active_transfers, active, active) else "Everything at your pace",
                    style = MaterialTheme.typography.headlineMedium)
                Text(if (attention > 0) "$attention ${if (attention == 1) "transfer needs" else "transfers need"} your attention" else "Your downloads, from start to saved.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.admissionNotice?.let { InfoBanner("Resume when you are ready", it) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(session.transferFilter == TransferFilter.ALL, { model.setTransferFilter(TransferFilter.ALL) }, label = { Text("All (${state.tasks.size})") })
                    FilterChip(session.transferFilter == TransferFilter.ACTIVE, { model.setTransferFilter(TransferFilter.ACTIVE) }, label = { Text("Active ($active)") })
                    FilterChip(session.transferFilter == TransferFilter.ATTENTION, { model.setTransferFilter(TransferFilter.ATTENTION) }, label = { Text("Needs attention ($attention)") })
                }
            }
        }
        if (tasks.isEmpty()) item("empty_filter") {
            EmptyState(Icons.Outlined.FilterList, "No transfers here", "Choose All to see paused and finished downloads.",
                actionLabel = "Show all", onAction = { model.setTransferFilter(TransferFilter.ALL) })
        }
        items(tasks, key = { it.id }) { task ->
            TransferRow(task, task.id in session.selectedIds, session.selectionKind == SelectionKind.TRANSFERS, state.settings.reducedMotion,
                onClick = { model.navigate(BmdScreen.TRANSFER_DETAIL, task.id) },
                onSelect = { if (session.selectionKind == null) model.beginSelection(SelectionKind.TRANSFERS, task.id) else model.toggleSelection(task.id) },
                onAction = { model.taskAction(task.id, it) })
        }
    }
}

@Composable
fun TransferDetailScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val task = state.tasks.find { it.id == session.route.id }
    var diagnosticsOpen by rememberSaveable(session.route.id) { mutableStateOf(false) }
    if (task == null) {
        EmptyState(Icons.Outlined.Info, "Transfer no longer in the list", "It may have been removed from history. Published files can still be found in Files.",
            actionLabel = "Open Files", onAction = { model.selectTab(BmdScreen.FILES) })
        return
    }
    ScreenBody {
        ContentCard {
            FileTypeIcon(task.mimeType)
            Text(task.name, style = MaterialTheme.typography.headlineMedium)
            TaskStatus(task)
            ProgressSummary(task, state.settings.reducedMotion)
            if (task.stage == TransferStage.SAVING) Text("Download finished. Saving to ${task.destination.displayName}…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.stage == TransferStage.VERIFYING) Text("Checking the complete file against your expected SHA-256 before saving.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.sha256Verified) Text("Matches your expected SHA-256", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (TaskOperation.PAUSE in task.permittedActions) Button({ model.taskAction(task.id, TaskOperation.PAUSE) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !session.busy) { Text(stringResource(R.string.action_pause)) }
            if (TaskOperation.RESUME in task.permittedActions) Button({ model.taskAction(task.id, TaskOperation.RESUME) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !session.busy) {
                Text(if (task.stage == TransferStage.WAITING_VERIFY) "Continue verification" else stringResource(R.string.action_resume))
            }
            if (task.stage == TransferStage.COMPLETE) {
                val file = state.files.find { it.id == task.publishedFileId || it.taskId == task.id }
                if (file != null) {
                    Button({ model.openFile(file) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = file.accessible) { Text(stringResource(R.string.action_open)) }
                    OutlinedButton({ model.shareFile(file) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = file.accessible) { Text(stringResource(R.string.action_share)) }
                } else TextButton({ model.selectTab(BmdScreen.FILES) }) { Text("Open Files") }
            }
        }
        TaskRecoveryPanel(task, session.busy, model)
        ContentCard {
            KeyValue("Source", task.origin)
            HorizontalDivider()
            KeyValue("Save location", task.destination.displayName)
            KeyValue("Network", if (task.networkPolicy == NetworkPolicy.UNMETERED) "Unmetered only" else "Any network")
            if (task.createdAt > 0) KeyValue("Added", formatDate(task.createdAt))
            if (task.expectedSha256 != null) KeyValue("Expected SHA-256", task.expectedSha256)
            if (task.stage !in setOf(TransferStage.COMPLETE, TransferStage.CANCELED)) TextButton({ model.destination(task.id) }) { Text("Change save location") }
        }
        if (task.diagnostics != null || task.errorCode != null) ContentCard {
            TextButton({ diagnosticsOpen = !diagnosticsOpen }, Modifier.fillMaxWidth()) { Text(if (diagnosticsOpen) "Hide technical details" else "Show technical details") }
            if (diagnosticsOpen) {
                task.errorCode?.let { KeyValue("Error code", it) }
                task.diagnostics?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
        if (TaskOperation.CANCEL in task.permittedActions) OutlinedButton({ model.taskAction(task.id, TaskOperation.CANCEL) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !session.busy) {
            Text("Cancel download", color = MaterialTheme.colorScheme.error)
        }
        if (task.stage == TransferStage.COMPLETE || task.stage == TransferStage.CANCELED || task.stage == TransferStage.FAILED) TextButton({ model.requestRemoveTransfers(setOf(task.id)) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Remove from transfer history") }
    }
}

@Composable
private fun TaskRecoveryPanel(task: UiTask, busy: Boolean, model: BmdViewModel) {
    when {
        task.publicationUncertain -> ContentCard {
            InfoBanner("The save needs to be checked", task.detail ?: "The destination may already contain your file. Downloaded data is kept until the save is resolved.", warning = true)
            Button({ model.locateSavedFile(task.id) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy) { Text("Locate the saved file") }
            Text("Choose the exact document. Black Marlin compares its contents with the retained download; its name and size alone are not enough.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (TaskOperation.SAVE_SEPARATE_COPY in task.permittedActions) OutlinedButton({ model.taskAction(task.id, TaskOperation.SAVE_SEPARATE_COPY) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Save a separate copy") }
            if (TaskOperation.STOP_SAVING_KEEP_DATA in task.permittedActions) TextButton({ model.taskAction(task.id, TaskOperation.STOP_SAVING_KEEP_DATA) }, enabled = !busy) { Text("Stop saving, keep data") }
            if (TaskOperation.DISCARD_DOWNLOADED_DATA in task.permittedActions) TextButton({ model.taskAction(task.id, TaskOperation.DISCARD_DOWNLOADED_DATA) }, enabled = !busy) { Text("Discard downloaded data", color = MaterialTheme.colorScheme.error) }
        }
        task.stage == TransferStage.WAITING_SAVE -> ContentCard {
            InfoBanner("Downloaded, waiting to save", task.detail ?: "Your downloaded data is safe. Retry saving locally or choose another location. The file does not need to download again.")
            if (TaskOperation.RETRY_SAVE in task.permittedActions) Button({ model.taskAction(task.id, TaskOperation.RETRY_SAVE) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy) { Text("Retry save") }
            OutlinedButton({ model.destination(task.id) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Choose another location") }
            if (TaskOperation.SAVE_SEPARATE_COPY in task.permittedActions) TextButton({ model.taskAction(task.id, TaskOperation.SAVE_SEPARATE_COPY) }, enabled = !busy) { Text("Save a separate copy") }
            if (TaskOperation.STOP_SAVING_KEEP_DATA in task.permittedActions) TextButton({ model.taskAction(task.id, TaskOperation.STOP_SAVING_KEEP_DATA) }, enabled = !busy) { Text("Stop saving, keep data") }
            if (TaskOperation.DISCARD_DOWNLOADED_DATA in task.permittedActions) TextButton({ model.taskAction(task.id, TaskOperation.DISCARD_DOWNLOADED_DATA) }, enabled = !busy) { Text("Discard downloaded data", color = MaterialTheme.colorScheme.error) }
        }
        task.stage == TransferStage.NEEDS_CREDENTIALS -> InfoBanner("This source requires authentication", task.detail ?: "Add a supported credential for ${task.origin}. The same URL can resume after its content is validated.", warning = true,
            action = "Edit source credentials", onAction = { model.openCredentials(task.origin, task.id) })
        task.stage == TransferStage.NEEDS_LINK -> InfoBanner("A new link is needed", task.detail ?: "The link may have expired or the source denied access. A replacement starts a new download after you confirm.", warning = true,
            action = "Replace link", onAction = { model.taskAction(task.id, TaskOperation.REPLACE_LINK) })
        task.stage == TransferStage.FILE_CHANGED -> InfoBanner("The file changed on the server", "Restart to avoid combining different versions. Your current partial is retained until you choose.", warning = true,
            action = "Restart", onAction = { model.taskAction(task.id, TaskOperation.RESTART) })
        task.stage == TransferStage.WAITING_SPACE -> InfoBanner("More space is needed", task.detail ?: "The download is paused safely. Free space and then resume it.", warning = true,
            action = "Review storage", onAction = { model.navigate(BmdScreen.STORAGE) })
        task.stage == TransferStage.WAITING_ANDROID -> InfoBanner("Waiting for Android", task.detail ?: "Android could not admit this work in the background. Resume while the app is visible when you are ready.")
        task.stage == TransferStage.INTERRUPTED -> InfoBanner("Ready to recover", task.detail ?: "The previous transfer was interrupted. Resume to validate and continue the saved partial.")
        task.stage == TransferStage.WAITING_VERIFY -> InfoBanner("Downloaded, waiting to verify", task.detail ?: "The expected checksum still needs to be checked. Continue verification while the app is visible.")
        task.stage == TransferStage.WAITING_NETWORK -> InfoBanner("Waiting for an eligible connection", task.detail ?: if (task.networkPolicy == NetworkPolicy.UNMETERED) "This task is waiting for an unmetered network. You can pause it while you wait." else "The download will be retried when an eligible connection is available.")
        task.stage == TransferStage.FAILED -> InfoBanner("This transfer needs attention", task.detail ?: "The download could not continue. Check its details before retrying.", warning = true,
            action = if (TaskOperation.RETRY in task.permittedActions) "Retry" else null,
            onAction = if (TaskOperation.RETRY in task.permittedActions) ({ model.taskAction(task.id, TaskOperation.RETRY) }) else null)
        task.stage == TransferStage.CANCELED -> InfoBanner("Download canceled", task.detail ?: "The transfer has stopped. Any file already published is preserved.")
        task.detail != null && task.stage != TransferStage.COMPLETE -> InfoBanner(task.stage.label, task.detail)
    }
}
