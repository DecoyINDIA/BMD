package com.blackmarlin.downloader.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: BmdAppState, model: BmdViewModel) {
    ScreenBody {
        ContentCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BrandEmblem()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Black Marlin", style = MaterialTheme.typography.titleLarge)
                    Text("Your downloads. Your device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionTitle("Make it work for you")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRow(Icons.Outlined.Wifi, "Network and performance", if (state.settings.defaultNetwork == NetworkPolicy.UNMETERED) "Unmetered by default" else "Any network by default", { model.navigate(BmdScreen.NETWORK) })
            SettingsRow(Icons.Outlined.Storage, "Storage and cleanup", state.settings.defaultDestination.displayName, { model.navigate(BmdScreen.STORAGE) })
            SettingsRow(Icons.Outlined.ColorLens, "Appearance", state.settings.theme.label(), { model.navigate(BmdScreen.APPEARANCE) })
            SettingsRow(Icons.Outlined.NotificationsNone, "Notifications", if (state.notificationPermissionGranted && state.transferChannelEnabled) "Progress and file-ready updates" else "Some notifications are off", { model.navigate(BmdScreen.NOTIFICATIONS) })
        }
        SectionTitle("Source credentials")
        ContentCard {
            Text("Optional credentials for the sites you download from. There is no Black Marlin account.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.credentials.forEach { credential ->
                SettingsRow(Icons.Outlined.Key, credential.origin, if (credential.kind == CredentialKind.BASIC) "Remembered HTTP Basic credential" else "Remembered Bearer credential",
                    { model.openCredentials(credential.origin, fromSettings = true) })
            }
            OutlinedButton({ model.openCredentials(fromSettings = true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage source credential") }
        }
        SectionTitle("More about Black Marlin")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRow(Icons.Outlined.HelpOutline, "Help and diagnostics", "Supported links, recovery and a local report", { model.navigate(BmdScreen.HELP) })
            SettingsRow(Icons.Outlined.Info, "About, privacy and licenses", state.versionName.takeIf { it.isNotBlank() }?.let { "Version $it" }, { model.navigate(BmdScreen.ABOUT) })
        }
    }
}

@Composable
fun NetworkScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val settings = state.settings
    var network by rememberSaveable(settings.defaultNetwork) { mutableStateOf(settings.defaultNetwork) }
    var active by rememberSaveable(settings.maxActiveTransfers) { mutableStateOf(settings.maxActiveTransfers) }
    var segments by rememberSaveable(settings.segmentsPerTransfer) { mutableStateOf(settings.segmentsPerTransfer) }
    var global by rememberSaveable(settings.globalSegmentLimit) { mutableStateOf(settings.globalSegmentLimit) }
    var perHost by rememberSaveable(settings.perHostLimit) { mutableStateOf(settings.perHostLimit) }
    var speed by rememberSaveable(settings.speedLimitBytesPerSecond) { mutableStateOf(if (settings.speedLimitBytesPerSecond == 0L) "" else (settings.speedLimitBytesPerSecond / 1024).toString()) }
    val speedValue = if (speed.isBlank()) 0L else speed.toLongOrNull()
    val validSpeed = speedValue != null && speedValue in 0..1_048_576L
    ScreenBody(Modifier.imePadding()) {
        PageIntro("A sensible pace", "Balance download speed, data use and battery. These defaults apply to future requests or at a safe transfer boundary.")
        ContentCard {
            SectionTitle("Default network")
            ChoiceRow("Unmetered only", "Wait for a connection Android marks as unmetered.", network == NetworkPolicy.UNMETERED, { network = NetworkPolicy.UNMETERED })
            ChoiceRow("Any network", "Allow future downloads to use metered data.", network == NetworkPolicy.ANY, { network = NetworkPolicy.ANY })
        }
        ContentCard {
            SectionTitle("Concurrent work on unmetered networks")
            NumberStepper("Active downloads", active, 1..2) { active = it }
            NumberStepper("Connections per download", segments, 1..2) { segments = it }
            NumberStepper("Total connection limit", global, 1..4) { global = it }
            NumberStepper("Connections per source", perHost, 1..2) { perHost = it }
            Text("The lowest applicable limit wins. Sources without usable range support use one connection.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoBanner("Keep metered use predictable", "Metered downloads use one active transfer and one connection. More connections do not always mean more speed.")
        ContentCard {
            OutlinedTextField(speed, { speed = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                label = { Text("Limit per transfer in KiB/s") }, supportingText = { Text("Applies to each active transfer. Leave empty or enter 0 for no limit. 1 MiB/s = 1,024 KiB/s.") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !validSpeed)
            if (!validSpeed) InlineError("Choose a limit between 0 and 1,048,576 KiB/s.")
        }
        Button({ model.updateSettings(settings.copy(defaultNetwork = network, maxActiveTransfers = active,
            segmentsPerTransfer = segments, globalSegmentLimit = global, perHostLimit = perHost,
            speedLimitBytesPerSecond = (speedValue ?: 0) * 1024)) }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = validSpeed && !session.busy) { Text("Save defaults") }
        TextButton(model::resetNetworkSettings, Modifier.align(Alignment.CenterHorizontally), enabled = !session.busy) { Text("Reset network defaults") }
    }
}

@Composable
private fun NumberStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton({ onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Decrease $label" }) { Icon(Icons.Outlined.Remove, null) }
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { contentDescription = "$label, $value" })
            OutlinedButton({ onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Increase $label" }) { Icon(Icons.Outlined.Add, null) }
        }
    }
}

@Composable
fun StorageScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val candidates = state.storage.cleanupCandidates
    val selectedIds = selected.intersect(candidates.map { it.taskId }.toSet())
    ScreenBody {
        PageIntro("Room for what matters", "See what Black Marlin is keeping and choose what to remove.")
        ContentCard {
            KeyValue("Available on this device", state.storage.availableBytes?.let(::formatBytes) ?: "Not reported")
            HorizontalDivider()
            KeyValue("Downloads in progress and waiting to save", formatBytes(state.storage.stagedBytes))
            KeyValue("Retained partial data", formatBytes(state.storage.retainedPartialBytes))
            KeyValue("Published files indexed by Black Marlin", formatBytes(state.storage.publishedBytes))
        }
        SectionTitle("Default save location")
        DestinationRow(state.settings.defaultDestination) { model.destination("settings") }
        SectionTitle("Review partial data")
        InfoBanner("Only safe cleanup candidates", "Active transfers and uncertain saves are excluded. Select the settled partials you no longer need; nothing is selected automatically.")
        if (candidates.isEmpty()) ContentCard { Text("No settled partial downloads need cleanup.", style = MaterialTheme.typography.bodyLarge) }
        else ContentCard {
            candidates.forEach { candidate ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(candidate.taskId in selectedIds, { checked -> selected = if (checked) (selected + candidate.taskId).distinct() else selected - candidate.taskId },
                        modifier = Modifier.semantics { contentDescription = "Select ${candidate.name} for cleanup" })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(candidate.name, style = MaterialTheme.typography.titleMedium)
                        Text("${formatBytes(candidate.bytes)} · ${candidate.reason}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedButton({ model.requestCleanup(selectedIds) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = selectedIds.isNotEmpty() && !session.busy) { Text("Review cleanup (${selectedIds.size})") }
        }
    }
}

@Composable
fun AppearanceScreen(state: BmdAppState, model: BmdViewModel) {
    ScreenBody {
        PageIntro("Comfortable at a glance", "Follow your device, or choose the appearance you prefer.")
        ContentCard {
            ThemeMode.entries.forEach { mode -> ChoiceRow(mode.label(), null, state.settings.theme == mode,
                { model.updateSettings(state.settings.copy(theme = mode)) }) }
        }
        ContentCard {
            ToggleRow("Reduce motion", "Use still progress tracks when the total size is unknown. System animation settings are also respected.", state.settings.reducedMotion) {
                model.updateSettings(state.settings.copy(reducedMotion = it))
            }
        }
        ContentCard {
            SectionTitle("Appearance preview")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BrandEmblem()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Clear, calm, readable", style = MaterialTheme.typography.titleMedium)
                    Text("Text follows your device's font size. Controls remain easy to reach.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(state: BmdAppState, model: BmdViewModel) {
    ScreenBody {
        PageIntro("Know when it is ready", "See background progress and saved-file updates without reopening Black Marlin.")
        if (Build.VERSION.SDK_INT >= 33 && !state.notificationPermissionGranted) InfoBanner("Notifications are not allowed", "Downloads remain visible inside the app. You can allow notifications when you are ready.", warning = true,
            action = "Allow notifications", onAction = model::requestNotifications)
        ContentCard {
            KeyValue("Notification permission", if (Build.VERSION.SDK_INT < 33) "Managed in Android notification settings" else if (state.notificationPermissionGranted) "Allowed" else "Not allowed")
            KeyValue("Transfer progress channel", if (state.transferChannelEnabled) "Enabled" else "Disabled")
            KeyValue("Completed files channel", if (state.completionChannelEnabled) "Enabled" else "Disabled")
            OutlinedButton({ model.openNotificationSettings() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Android notification settings") }
        }
        ContentCard {
            ToggleRow("Keep file names private", "Use a neutral notification title instead of the file name.", state.settings.privateNotifications) {
                model.updateSettings(state.settings.copy(privateNotifications = it))
            }
            HorizontalDivider()
            Text("Notification text preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.settings.privateNotifications) "Black Marlin · Download in progress" else "Black Marlin · Your file name", style = MaterialTheme.typography.titleMedium)
            Text("Source URLs and credentials never appear in notifications.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun HelpScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    ScreenBody {
        PageIntro("A little help, on hand", "These guides are available offline.")
        HelpSection("What links are supported?", "Use a direct HTTP or HTTPS link to a file. Webpages, video-platform extraction, torrents, FTP and cloud-account sync are not supported. Share a link from your browser or tap Paste in Add a download. Black Marlin never watches your clipboard.")
        HelpSection("How do pause and resume work?", "Pause keeps received data. Resume checks whether the source still describes the same file before continuing. If the source does not support safe resume, Black Marlin asks to restart. A changed URL starts the file again only after you confirm.")
        HelpSection("Why does Android pause background work?", "Android controls when background work can run. Battery restrictions, unavailable networks or a stop action can interrupt a transfer. Open its details and choose Resume. Force-stopping the app prevents background work until you open it again; stopped work is not silently restarted.")
        HelpSection("Downloaded, but not yet in Files?", "Downloading and saving are separate stages. If a destination is full or unavailable, the downloaded data is retained. Retry save while the app is visible or choose another location. If a provider may already have saved a file, locate that exact document so Black Marlin can compare its contents.")
        HelpSection("Remove from list or delete?", "Remove from list only clears Black Marlin's index. Delete file removes the saved file from its location after confirmation. Clearing transfer history never deletes a published file. Partial data is managed separately in Storage and cleanup.")
        HelpSection("Where are source credentials used?", "HTTP Basic credentials and Bearer tokens are restricted to one exact HTTPS origin, including the port. They are encrypted on this device. Remember for this origin is an optional choice for future tasks. Credentials do not follow redirects to other origins.")
        ContentCard {
            SectionTitle("Local diagnostic report")
            Text("Create and review a redacted report before sharing it yourself. URLs, request credentials and private file paths must not be included.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.versionName.isNotBlank()) KeyValue("App version", state.versionName)
            if (state.engineRevision.isNotBlank()) KeyValue("Engine revision", state.engineRevision)
            OutlinedButton(model::loadDiagnostics, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !session.diagnosticsLoading) {
                if (session.diagnosticsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (session.diagnostics == null) "Create report preview" else "Refresh report")
            }
            session.diagnostics?.let { report ->
                OutlinedTextField(report, {}, Modifier.fillMaxWidth(), readOnly = true, minLines = 7, maxLines = 15,
                    label = { Text("Report preview") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                Button(model::shareDiagnostics, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share this report") }
            }
        }
        TextButton({ model.openExternal("https://github.com/DecoyINDIA/BMD/issues") }) { Text("Open project support") }
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    ContentCard {
        TextButton({ expanded = !expanded }, Modifier.fillMaxWidth()) { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)) }
        if (expanded) Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun AboutScreen(state: BmdAppState, model: BmdViewModel) {
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val licenseText by produceState<String?>(null, license) {
        value = license?.let { file -> withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
                .getOrElse { "The bundled license notice could not be opened. The project source contains the license and dependency information." }
        } }
    }
    ScreenBody {
        ContentCard {
            BrandEmblem(large = true)
            Text("Black Marlin Downloader", style = MaterialTheme.typography.headlineMedium)
            Text("Native Android. A Rust download engine.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.versionName.isNotBlank()) KeyValue("Version", state.versionName)
            if (state.engineRevision.isNotBlank()) KeyValue("Engine revision", state.engineRevision)
        }
        ContentCard {
            SectionTitle("Privacy, in plain language")
            Text("Your transfer history, save locations and settings are stored on this device. Download links are kept while needed to recover your transfers. Black Marlin does not require an account or include advertising or analytics tracking.", style = MaterialTheme.typography.bodyLarge)
            Text("Requests go to the sources you choose. Those sources can see the network requests they receive. If you choose a cloud document provider, that provider handles its own storage and network access.", style = MaterialTheme.typography.bodyLarge)
            Text("Source credentials are encrypted locally and limited to their exact HTTPS origin. Task-only credentials are removed after publication or cancellation cleanup. Remembered credentials remain until you forget them. App data and credentials are excluded from Android backup.", style = MaterialTheme.typography.bodyLarge)
            Text("Clipboard text is read only when you tap Paste. Diagnostics are shared only when you review a report and use the Android share sheet. File deletion always has its own confirmation.", style = MaterialTheme.typography.bodyLarge)
        }
        ContentCard {
            SectionTitle("Open source")
            Text("Black Marlin is licensed under GNU GPL version 3 or later. The source and corresponding build instructions are available from the project repository.", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton({ model.openExternal("https://github.com/DecoyINDIA/BMD") }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View project source") }
            TextButton({ license = if (license == "GPL-3.0.txt") null else "GPL-3.0.txt" }) { Text("Read GPL license offline") }
            TextButton({ license = if (license == "THIRD_PARTY_RUST_NOTICES.txt") null else "THIRD_PARTY_RUST_NOTICES.txt" }) { Text("Rust dependency notices") }
            TextButton({ license = if (license == "THIRD_PARTY_ANDROID_NOTICES.txt") null else "THIRD_PARTY_ANDROID_NOTICES.txt" }) { Text("Android dependency notices") }
            if (license != null) {
                HorizontalDivider()
                if (licenseText == null) Text("Opening bundled notice…") else SelectionContainer { Text(licenseText.orEmpty(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
