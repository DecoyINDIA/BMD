package com.blackmarlin.downloader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackmarlin.downloader.R
import kotlinx.coroutines.launch

private val rootScreens = setOf(BmdScreen.TRANSFERS, BmdScreen.FILES, BmdScreen.SETTINGS)
private data class NavigationItem(val screen: BmdScreen, val label: Int, val icon: ImageVector)
private val navigationItems = listOf(
    NavigationItem(BmdScreen.TRANSFERS, R.string.nav_transfers, Icons.Outlined.Download),
    NavigationItem(BmdScreen.FILES, R.string.nav_files, Icons.Outlined.FolderOpen),
    NavigationItem(BmdScreen.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BmdApp(model: BmdViewModel, onPlatformEffect: (BmdEffect) -> Unit) {
    val state by model.appState.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        model.effects.collect { effect ->
            if (effect is BmdEffect.Message) scope.launch { snackbar.showSnackbar(effect.text) }
            else onPlatformEffect(effect)
        }
    }
    BackHandler(session.route.screen !in rootScreens || session.selectionKind != null || session.dialog != null) { model.back() }
    BmdTheme(state.settings.theme) {
        val darkTheme = MaterialTheme.colorScheme.background.luminance() < .5f
        LaunchedEffect(darkTheme) { onPlatformEffect(BmdEffect.UpdateSystemBars(darkTheme)) }
        DisposableEffect(session.route.screen) {
            onPlatformEffect(BmdEffect.ProtectCredentialWindow(session.route.screen == BmdScreen.CREDENTIALS))
            onDispose { onPlatformEffect(BmdEffect.ProtectCredentialWindow(false)) }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val wide = maxWidth >= 840.dp
                val selection = session.selectionKind != null
                val screen = session.route.screen
                val selectedTab = selectedNavigationTab(session.route)
                Row(Modifier.fillMaxSize()) {
                    if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxHeight()) {
                        BrandEmblem(Modifier.padding(vertical = 20.dp))
                        navigationItems.forEach { item ->
                            NavigationRailItem(selectedTab == item.screen, { model.selectTab(item.screen) },
                                icon = { Icon(item.icon, null) }, label = { Text(stringResource(item.label)) }, alwaysShowLabel = true)
                        }
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            TopAppBar(
                                title = { Text(if (selection) pluralStringResource(R.plurals.items_selected, session.selectedIds.size, session.selectedIds.size)
                                    else stringResource(screen.titleResource())) },
                                navigationIcon = {
                                    if (selection) IconButton(model::clearSelection) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close)) }
                                    else if (screen !in rootScreens) IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                                },
                                actions = {
                                    if (selection) TextButton({
                                        model.selectAll(if (session.selectionKind == SelectionKind.FILES) visibleFiles(state, session).map { it.id }.toSet()
                                            else visibleTransfers(state, session).map { it.id }.toSet())
                                    }) { Text(stringResource(R.string.action_select_all)) }
                                    else if (screen == BmdScreen.TRANSFERS || screen == BmdScreen.FILES) {
                                        IconButton({ model.navigate(BmdScreen.SEARCH) }) { Icon(Icons.Outlined.Search, stringResource(R.string.title_search)) }
                                        if ((screen == BmdScreen.TRANSFERS && state.tasks.isNotEmpty()) || (screen == BmdScreen.FILES && state.files.isNotEmpty())) {
                                            IconButton({ model.beginSelection(if (screen == BmdScreen.FILES) SelectionKind.FILES else SelectionKind.TRANSFERS) }) {
                                                Icon(Icons.Outlined.Checklist, stringResource(R.string.action_select))
                                            }
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                            )
                        },
                        bottomBar = {
                            if (selection) SelectionActions(state, session, model)
                            else if (!wide && screen in rootScreens) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                navigationItems.forEach { item ->
                                    NavigationBarItem(selectedTab == item.screen, { model.selectTab(item.screen) },
                                        icon = { Icon(item.icon, null) }, label = { Text(stringResource(item.label)) })
                                }
                            }
                        },
                        floatingActionButton = {
                            if (!selection && screen in setOf(BmdScreen.TRANSFERS, BmdScreen.FILES) && (state.tasks.isNotEmpty() || state.files.isNotEmpty())) {
                                ExtendedFloatingActionButton(onClick = model::addLink, icon = { Icon(Icons.Outlined.Add, null) },
                                    text = { Text(stringResource(R.string.action_add_link)) })
                            }
                        },
                        snackbarHost = { SnackbarHost(snackbar) },
                    ) { insets ->
                        Box(Modifier.padding(insets).consumeWindowInsets(insets).fillMaxSize().testTag("screen-${screen.name.lowercase()}")) {
                            if (state.loading && screen in rootScreens) Column(Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.label_loading))
                            } else if (wide && screen == BmdScreen.TRANSFER_DETAIL) Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(.44f)) { TransfersScreen(state, session, model) }
                                VerticalDivider(Modifier.fillMaxHeight())
                                Box(Modifier.weight(.56f)) { TransferDetailScreen(state, session, model) }
                            } else if (wide && screen == BmdScreen.FILE_DETAIL) Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(.44f)) { FilesScreen(state, session, model) }
                                VerticalDivider(Modifier.fillMaxHeight())
                                Box(Modifier.weight(.56f)) { FileDetailScreen(state, session, model) }
                            } else BmdScreenContent(state, session, model)
                        }
                    }
                }
                BmdDialogs(state, session, model)
            }
        }
    }
}

@Composable
private fun BmdScreenContent(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    when (session.route.screen) {
        BmdScreen.TRANSFERS -> TransfersScreen(state, session, model)
        BmdScreen.ADD_LINK -> AddLinkScreen(session, model)
        BmdScreen.REVIEW -> ReviewDownloadScreen(state, session, model)
        BmdScreen.DESTINATION -> DestinationScreen(state, session, model)
        BmdScreen.TRANSFER_DETAIL -> TransferDetailScreen(state, session, model)
        BmdScreen.FILES -> FilesScreen(state, session, model)
        BmdScreen.FILE_DETAIL -> FileDetailScreen(state, session, model)
        BmdScreen.SEARCH -> SearchScreen(state, session, model)
        BmdScreen.SETTINGS -> SettingsScreen(state, model)
        BmdScreen.NETWORK -> NetworkScreen(state, session, model)
        BmdScreen.STORAGE -> StorageScreen(state, session, model)
        BmdScreen.APPEARANCE -> AppearanceScreen(state, model)
        BmdScreen.NOTIFICATIONS -> NotificationsScreen(state, model)
        BmdScreen.CREDENTIALS -> CredentialsScreen(state, session, model)
        BmdScreen.HELP -> HelpScreen(state, session, model)
        BmdScreen.ABOUT -> AboutScreen(state, model)
    }
}

private fun BmdScreen.titleResource(): Int = when (this) {
    BmdScreen.TRANSFERS -> R.string.nav_transfers
    BmdScreen.FILES -> R.string.nav_files
    BmdScreen.SETTINGS -> R.string.nav_settings
    BmdScreen.ADD_LINK -> R.string.title_add_link
    BmdScreen.REVIEW -> R.string.title_review
    BmdScreen.DESTINATION -> R.string.title_destination
    BmdScreen.TRANSFER_DETAIL -> R.string.title_transfer
    BmdScreen.FILE_DETAIL -> R.string.title_file
    BmdScreen.SEARCH -> R.string.title_search
    BmdScreen.NETWORK -> R.string.title_network
    BmdScreen.STORAGE -> R.string.title_storage
    BmdScreen.APPEARANCE -> R.string.title_appearance
    BmdScreen.NOTIFICATIONS -> R.string.title_notifications
    BmdScreen.CREDENTIALS -> R.string.title_credentials
    BmdScreen.HELP -> R.string.title_help
    BmdScreen.ABOUT -> R.string.title_about
}

@Composable
private fun SelectionActions(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    Surface(shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val selected = session.selectedIds
            if (session.selectionKind == SelectionKind.TRANSFERS) {
                val tasks = state.tasks.filter { it.id in selected }
                TextButton({ model.bulkTaskAction(TaskOperation.PAUSE) }, enabled = !session.busy && tasks.any { TaskOperation.PAUSE in it.permittedActions }) { Text("Pause") }
                TextButton({ model.bulkTaskAction(TaskOperation.RESUME) }, enabled = !session.busy && tasks.any { TaskOperation.RESUME in it.permittedActions }) { Text("Resume") }
                TextButton({ model.requestRemoveTransfers(selected) }, enabled = !session.busy && selected.isNotEmpty()) { Text("Remove history") }
            } else {
                TextButton({ model.requestRemoveFiles(selected, false) }, enabled = !session.busy && selected.isNotEmpty()) { Text("Remove from list") }
                TextButton({ model.requestRemoveFiles(selected, true) }, enabled = !session.busy && selected.isNotEmpty()) { Text("Delete files", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
fun BmdDialogs(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val dialog = session.dialog ?: return
    when (dialog) {
        is BmdDialog.PlainHttp -> DecisionDialog("Allow an unencrypted connection?",
            "${dialog.origin} uses HTTP. The link, credentials and downloaded content may be visible or changed on this network. This permission applies only to this task. No request has been sent yet.",
            "Allow HTTP for this task", model::allowPlainHttp, "Cancel", model::dismissDialog)
        BmdDialog.Metered -> AlertDialog(onDismissRequest = model::dismissDialog,
            title = { Text("Use this metered network?") },
            text = { Text("Checking the source and downloading may use paid or limited data. You can allow this task once, or wait for an unmetered connection.") },
            confirmButton = { TextButton(model::allowMeteredOnce) { Text("Use this network once") } },
            dismissButton = { Column { TextButton(model::waitForUnmetered) { Text("Wait for unmetered") }; TextButton({ model.dismissDialog(); model.navigate(BmdScreen.NETWORK) }) { Text("Network settings") } } })
        BmdDialog.Notifications -> DecisionDialog("See progress outside the app",
            "Allow notifications to see active transfers and when files are ready. Android will ask for permission next. You can change this later in Settings.",
            "Allow notifications", model::allowNotifications, "Later", model::dismissDialog)
        is BmdDialog.CancelTask -> DecisionDialog("Cancel ${dialog.name}?",
            "Cancel stops this download and removes its partial data when safe. Pause instead to keep your progress. A file already saved is preserved; an uncertain save keeps its data until it can be checked.",
            "Cancel download", { model.confirmTaskAction(dialog.taskId, TaskOperation.CANCEL, dialog.generation) }, "Keep downloading", model::dismissDialog, destructive = true)
        is BmdDialog.RestartTask -> DecisionDialog(if (dialog.replacement) "Start again with this new link?" else "Restart ${dialog.name}?",
            if (dialog.replacement) "This new link starts the file again. The old partial is kept until you confirm. Black Marlin will stop the previous writer before starting the new version."
            else "The file may have changed on the server. Restarting avoids combining different versions and replaces this task's partial download.",
            "Restart download", { model.confirmRestart(dialog) }, "Keep paused", model::dismissDialog)
        is BmdDialog.Duplicate -> AlertDialog(onDismissRequest = model::dismissDialog,
            title = { Text("This download already exists") },
            text = { Text("Open the existing transfer, or save another copy using a safe, distinct file name. Existing files are never silently overwritten.") },
            confirmButton = { TextButton({ model.dismissDialog(); model.navigate(BmdScreen.TRANSFER_DETAIL, dialog.taskId) }) { Text("Open existing") } },
            dismissButton = { Column { TextButton({ model.dismissDialog(); model.startDownload(saveAnother = true) }) { Text("Save another copy") }; TextButton(model::dismissDialog) { Text("Cancel") } } })
        is BmdDialog.RemoveFiles -> DecisionDialog(if (dialog.delete) "Delete ${dialog.ids.size} ${if (dialog.ids.size == 1) "file" else "files"}?" else "Remove from Files?",
            if (dialog.delete) "This removes the selected files from their save locations. Other apps may no longer be able to open them. This action cannot be undone in Black Marlin."
            else "Only the selected entries are removed from Black Marlin. Their saved files remain in their locations.",
            if (dialog.delete) "Delete files" else "Remove from list", { model.removeFiles(dialog.ids, dialog.delete) }, "Keep files", model::dismissDialog, destructive = dialog.delete)
        is BmdDialog.RemoveTransfers -> DecisionDialog("Remove transfer history?", "Remove ${dialog.ids.size} selected entries from Transfers. Published files remain in Files and in their save locations. Active work must be stopped before its history can be removed.",
            "Remove history", { model.removeTransfers(dialog.ids) }, "Keep history", model::dismissDialog)
        is BmdDialog.Cleanup -> DecisionDialog("Remove ${formatBytes(dialog.bytes)} of partial data?", "This clears the selected settled partial downloads. They will need to start again if you download them later. Active transfers and uncertain saves are excluded.",
            "Remove partial data", { model.cleanup(dialog.ids) }, "Keep data", model::dismissDialog, destructive = true)
        is BmdDialog.ForgetCredential -> DecisionDialog("Forget this source credential?", "Future downloads from ${dialog.origin} will ask for authentication again. Other origins are unaffected.",
            "Forget credential", { model.forgetCredential(dialog.origin) }, "Keep credential", model::dismissDialog, destructive = true)
        is BmdDialog.SeparateCopy -> DecisionDialog("Save a separate copy?", "The previous save could not be confirmed. A copy may already exist at the destination. This creates another document from the retained download and may leave a duplicate. Inspect the location first if you are unsure.",
            "Save separate copy", { model.confirmTaskAction(dialog.taskId, TaskOperation.SAVE_SEPARATE_COPY, dialog.generation) }, "Go back", model::dismissDialog)
        is BmdDialog.StopSaving -> DecisionDialog("Stop saving and keep the data?", "Black Marlin will retain the downloaded data and the existing save information. You can inspect the destination or resume recovery later. No guessed document is deleted.",
            "Stop saving, keep data", { model.confirmTaskAction(dialog.taskId, TaskOperation.STOP_SAVING_KEEP_DATA, dialog.generation) }, "Go back", model::dismissDialog)
        is BmdDialog.DiscardDownloadedData -> DecisionDialog("Discard the retained download?",
            "This removes Black Marlin's private downloaded bytes for ${dialog.name} and ends recovery. You will no longer be able to compare or save those bytes. Files may still exist at the provider; they are left untouched and must be managed separately. This cannot be undone.",
            "Discard downloaded data", { model.confirmTaskAction(dialog.taskId, TaskOperation.DISCARD_DOWNLOADED_DATA, dialog.generation) }, "Keep data", model::dismissDialog, destructive = true)
        is BmdDialog.SharedLinks -> AlertDialog(onDismissRequest = model::dismissDialog,
            title = { Text("Choose a link") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One download at a time. Choose the direct file link to review.")
                dialog.links.forEach { url -> TextButton({ model.chooseSharedLink(url) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(url, maxLines = 3) } }
            } }, confirmButton = { TextButton(model::dismissDialog) { Text("Cancel") } })
        is BmdDialog.BatchFailures -> AlertDialog(onDismissRequest = model::dismissDialog,
            title = { Text("Some items need attention") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Other selected items completed their action.")
                dialog.failures.forEach { (id, reason) ->
                    val name = state.tasks.find { it.id == id }?.name ?: state.files.find { it.id == id }?.name ?: "Item no longer in list"
                    KeyValue(name, reason)
                }
            } }, confirmButton = { TextButton(model::dismissDialog) { Text("Done") } })
        BmdDialog.DiscardDraft -> DecisionDialog("Discard this draft?", "The link, save options and unsaved source credential will be cleared. Existing transfers are unaffected.",
            "Discard draft", model::discardDraft, "Keep editing", model::dismissDialog)
    }
}

@Composable
private fun DecisionDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, dismiss: String, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onDismiss) { Text(dismiss) } })
}
