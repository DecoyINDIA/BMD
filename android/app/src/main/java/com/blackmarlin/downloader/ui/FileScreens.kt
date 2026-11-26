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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.blackmarlin.downloader.R
import java.text.DateFormat
import java.util.Date

fun visibleFiles(state: BmdAppState, session: BmdSession): List<UiFile> {
    val filtered = state.files.filter { (session.fileKind == FileKind.ALL || it.kind() == session.fileKind) &&
        (session.fileQuery.isBlank() || it.name.contains(session.fileQuery.trim(), ignoreCase = true)) }
    return when (session.fileSort) {
        FileSort.NEWEST -> filtered.sortedByDescending { it.savedAt }
        FileSort.OLDEST -> filtered.sortedBy { it.savedAt }
        FileSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        FileSort.LARGEST -> filtered.sortedByDescending { it.bytes }
    }
}

@Composable
fun FilesScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    if (state.files.isEmpty()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Outlined.FolderOpen, stringResource(R.string.empty_files_title), stringResource(R.string.empty_files_body),
                actionLabel = stringResource(R.string.action_add_link), onAction = model::addLink)
        }
        return
    }
    val files = visibleFiles(state, session)
    var sortOpen by remember { mutableStateOf(false) }
    val groups = if (session.fileSort == FileSort.NEWEST || session.fileSort == FileSort.OLDEST)
        files.groupBy { if (it.savedAt > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.savedAt)) else "Saved files" }
        else linkedMapOf("${files.size} ${if (files.size == 1) "file" else "files"}" to files)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("files_header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved and ready", style = MaterialTheme.typography.headlineMedium)
                Text(pluralStringResource(R.plurals.saved_files, state.files.size, state.files.size) + " · " + formatBytes(state.files.sumOf { it.bytes }),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LocalSearchField(session.fileQuery, model::setFileQuery, stringResource(R.string.label_search_files))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FileKind.entries.forEach { kind -> FilterChip(session.fileKind == kind, { model.setFileKind(kind) }, label = { Text(kind.label()) }) }
                    }
                    Box {
                        IconButton({ sortOpen = true }) { Icon(Icons.Outlined.Sort, "Sort files, ${session.fileSort.label()}") }
                        DropdownMenu(sortOpen, { sortOpen = false }) {
                            FileSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.label()) }, onClick = { model.setFileSort(sort); sortOpen = false }) }
                        }
                    }
                }
            }
        }
        if (files.isEmpty()) item("empty_files_filter") {
            EmptyState(Icons.Outlined.Search, stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_body),
                actionLabel = "Clear filters", onAction = { model.setFileQuery(""); model.setFileKind(FileKind.ALL) })
        }
        groups.forEach { (label, group) ->
            item("group_$label") { SectionTitle(label) }
            items(group, key = { it.id }) { file -> FileRow(file, file.id in session.selectedIds, session.selectionKind == SelectionKind.FILES,
                onClick = { model.navigate(BmdScreen.FILE_DETAIL, file.id) },
                onSelect = { if (session.selectionKind == null) model.beginSelection(SelectionKind.FILES, file.id) else model.toggleSelection(file.id) }) }
        }
    }
}

@Composable
fun FileDetailScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val file = state.files.find { it.id == session.route.id }
    if (file == null) {
        ScreenBody { EmptyState(Icons.Outlined.FolderOpen, "File no longer in the list", "Removing an entry does not delete its file. Check the save location if you still need it.",
            actionLabel = "Back to Files", onAction = { model.selectTab(BmdScreen.FILES) }) }
        return
    }
    ScreenBody {
        ContentCard {
            FileTypeIcon(file.mimeType)
            Text(file.name, style = MaterialTheme.typography.headlineMedium)
            Text(formatBytes(file.bytes), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (file.sha256Verified) Text("Matches the expected SHA-256", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Button({ model.openFile(file) }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = file.accessible) { Text(stringResource(R.string.action_open)) }
            OutlinedButton({ model.shareFile(file) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = file.accessible) { Text(stringResource(R.string.action_share)) }
        }
        if (!file.accessible) InfoBanner("The saved file is unavailable", "It may have been moved or removed, or access to its location may have changed. Removing this list entry does not delete anything else.", warning = true,
            action = "Check again", onAction = model::refreshSystemState)
        ContentCard {
            KeyValue("Type", file.mimeType)
            KeyValue("Location", file.destinationName)
            if (file.savedAt > 0) KeyValue("Saved", formatDate(file.savedAt))
            file.taskId?.let { id -> if (state.tasks.any { it.id == id }) TextButton({ model.navigate(BmdScreen.TRANSFER_DETAIL, id) }) { Text("View transfer details") } }
        }
        ContentCard {
            SectionTitle("Manage this file")
            OutlinedButton({ model.requestRemoveFiles(setOf(file.id), false) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !session.busy) { Text("Remove from list") }
            Text("Keeps the file in its save location.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ model.requestRemoveFiles(setOf(file.id), true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !session.busy && file.accessible) { Text("Delete file", color = MaterialTheme.colorScheme.error) }
            Text("Removes the saved file from its location after confirmation.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SearchScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val query = session.searchQuery.trim()
    val tasks = if (query.isEmpty()) emptyList() else state.tasks.filter { it.name.contains(query, true) || it.origin.contains(query, true) }
    val files = if (query.isEmpty()) emptyList() else state.files.filter { it.name.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("search_field") { LocalSearchField(session.searchQuery, model::setSearchQuery, stringResource(R.string.label_search_all)) }
        if (query.isEmpty()) item("search_hint") {
            EmptyState(Icons.Outlined.Search, "Find it here", "Search your transfer names, source origins and saved files. Search stays on this device.")
        } else if (tasks.isEmpty() && files.isEmpty()) item("no_search_results") {
            EmptyState(Icons.Outlined.Search, stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_body))
        }
        if (tasks.isNotEmpty()) {
            item("transfer_search_heading") { SectionTitle("Transfers (${tasks.size})") }
            items(tasks, key = { "transfer_${it.id}" }) { task -> TransferRow(task, false, false, state.settings.reducedMotion,
                onClick = { model.navigate(BmdScreen.TRANSFER_DETAIL, task.id) },
                onSelect = { model.selectTab(BmdScreen.TRANSFERS); model.beginSelection(SelectionKind.TRANSFERS, task.id) },
                onAction = { model.taskAction(task.id, it) }) }
        }
        if (files.isNotEmpty()) {
            item("files_search_heading") { SectionTitle("Files (${files.size})") }
            items(files, key = { "file_${it.id}" }) { file -> FileRow(file, onClick = { model.navigate(BmdScreen.FILE_DETAIL, file.id) },
                onSelect = { model.selectTab(BmdScreen.FILES); model.beginSelection(SelectionKind.FILES, file.id) }) }
        }
    }
}

@Composable
private fun LocalSearchField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onValueChange("") }) { Icon(Icons.Outlined.Clear, stringResource(R.string.content_clear_query)) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
}

private fun FileKind.label(): String = when (this) {
    FileKind.ALL -> "All"
    FileKind.DOCUMENTS -> "Documents"
    FileKind.IMAGES -> "Images"
    FileKind.VIDEO -> "Video"
    FileKind.AUDIO -> "Audio"
    FileKind.ARCHIVES -> "Archives"
    FileKind.OTHER -> "Other"
}
private fun FileSort.label(): String = when (this) {
    FileSort.NEWEST -> "Newest first"
    FileSort.OLDEST -> "Oldest first"
    FileSort.NAME -> "Name A–Z"
    FileSort.LARGEST -> "Largest first"
}
