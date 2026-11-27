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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.blackmarlin.downloader.R

@Composable
fun AddLinkScreen(session: BmdSession, model: BmdViewModel) {
    val draft = session.draft
    ScreenBody(Modifier.imePadding()) {
        PageIntro(if (draft.replacementTaskId != null) "Choose a new source" else "Start with a link",
            if (draft.replacementTaskId != null) "A replacement link starts this file again. Your existing partial stays safe until you confirm."
            else "Paste a direct HTTP or HTTPS file link, or share one from your browser.")
        ContentCard {
            OutlinedTextField(draft.url, model::setDraftUrl, Modifier.fillMaxWidth().testTag("download-url"),
                label = { Text(stringResource(R.string.label_direct_link)) },
                placeholder = { Text("https://example.com/file.zip") },
                minLines = 3, maxLines = 6, isError = draft.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.reviewLink() }),
                trailingIcon = { if (draft.url.isNotEmpty()) IconButton({ model.setDraftUrl("") }) { Icon(Icons.Outlined.Clear, stringResource(R.string.action_clear)) } },
            )
            InlineError(draft.error)
            OutlinedButton(model::pasteLink, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.padding(end = 8.dp)); Text(stringResource(R.string.action_paste))
            }
            Text("The clipboard is read only when you tap Paste.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InfoBanner("Direct files work best", "Links to archives, documents, audio, video and other downloadable files are supported. A webpage or streaming-service link may not point to a file.")
        Button(model::reviewLink, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("download-review"), enabled = draft.url.isNotBlank() && !session.busy) {
            if (session.busy) { CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp); Text("Checking source…") }
            else Text(stringResource(R.string.action_review))
        }
        if (draft.url.isNotBlank()) TextButton(model::requestDiscardDraft, Modifier.align(Alignment.CenterHorizontally)) { Text("Discard draft") }
        Text(stringResource(R.string.label_no_account), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
fun ReviewDownloadScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val draft = session.draft
    val review = draft.review
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (review == null) {
        EmptyState(Icons.Outlined.Link, "Review the source again", "Source details are refreshed before a download is started.",
            actionLabel = "Review link", onAction = model::reviewLink)
        return
    }
    ScreenBody(Modifier.imePadding()) {
        PageIntro("Make it yours", "Check the file and save options before starting.")
        ContentCard {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                FileTypeIcon(review.mimeType)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatBytes(review.totalBytes), style = MaterialTheme.typography.headlineMedium)
                    Text(review.mimeType ?: "File type unknown", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            KeyValue(stringResource(R.string.label_source), review.origin)
            OutlinedTextField(draft.fileName, model::setDraftName, Modifier.fillMaxWidth().testTag("download-filename"), label = { Text(stringResource(R.string.label_file_name)) },
                singleLine = false, maxLines = 3, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            if (review.supportsResume != null) Text(if (review.supportsResume) "The source advertises byte-range support. Resume is validated when used." else "This source may require restarting after an interruption.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        review.warning?.let { InfoBanner("Check this source", it, warning = true) }
        if (review.possibleHtmlPage) InfoBanner("This may be a webpage", "The server returned an HTML content type. Use a direct file link if you expected a document, archive or media file.", warning = true)
        if (review.requiresCredentials) InfoBanner("Source authentication required", "Add a credential for this exact HTTPS origin before starting.", action = "Source credentials", onAction = { model.openCredentials(review.origin) })
        val sameVolumeCopy = draft.destination.kind == DestinationKind.PUBLIC_DOWNLOADS && android.os.Environment.isExternalStorageEmulated()
        val requiredSpace = com.blackmarlin.downloader.domain.StorageBudget.requiredFreeBytes(review.totalBytes, copyOnPrivateVolume = sameVolumeCopy)
        if (review.totalBytes != null && state.storage.availableBytes != null && requiredSpace > state.storage.availableBytes) {
            InfoBanner("Space is limited", "Keep about ${formatBytes(requiredSpace)} free while this file downloads and saves. This includes private staging, a final copy in Downloads when it shares device storage, and a 64 MiB safety margin. Other apps can change available space.", warning = true,
                action = "Storage and cleanup", onAction = { model.navigate(BmdScreen.STORAGE) })
        }
        SectionTitle(stringResource(R.string.label_destination))
        DestinationRow(draft.destination) { model.destination() }
        val destinationNeedsPicker = Build.VERSION.SDK_INT < 29 && draft.destination.kind == DestinationKind.PUBLIC_DOWNLOADS
        if (destinationNeedsPicker) InfoBanner("Choose a save folder", "On this Android version, choose a folder with Android's picker before starting.",
            action = "Choose folder", onAction = { model.destination() })
        ContentCard {
            SectionTitle("Network for this download")
            ChoiceRow("Unmetered only", "Wait for a network Android marks as unmetered.", draft.networkPolicy == NetworkPolicy.UNMETERED, { model.setDraftNetwork(NetworkPolicy.UNMETERED) })
            ChoiceRow("Any network", "Allow metered data for this download.", draft.networkPolicy == NetworkPolicy.ANY, { model.setDraftNetwork(NetworkPolicy.ANY) })
            if (draft.allowMeteredOnce) Text("Metered access allowed once for this task.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        ContentCard {
            TextButton({ advanced = !advanced }, Modifier.fillMaxWidth()) {
                Text("Advanced options", Modifier.weight(1f)); Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (advanced) {
                SettingsRow(Icons.Outlined.Key, "Source credentials", draft.credential?.let { "${it.kind.name.lowercase().replaceFirstChar(Char::uppercase)} for ${it.origin}" } ?: "None · separate from an app account",
                    { model.openCredentials(review.origin) })
                OutlinedTextField(draft.expectedSha256, model::setExpectedSha, Modifier.fillMaxWidth().testTag("download-sha256"),
                    label = { Text("Expected SHA-256 (optional)") }, minLines = 2, maxLines = 3,
                    supportingText = { Text("Use 64 hexadecimal characters from a trusted source. A server-provided checksum does not prove who created the file.") },
                    isError = !isValidExpectedSha256(draft.expectedSha256),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
            }
        }
        InlineError(draft.error)
        Button({ model.startDownload() }, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("download-start"), enabled = !session.busy && !destinationNeedsPicker && isValidExpectedSha256(draft.expectedSha256)) {
            if (session.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (draft.replacementTaskId != null) "Review restart" else stringResource(R.string.action_start))
        }
        Text("Your file appears in Files after saving is complete.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DestinationScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val target = session.route.context ?: "draft"
    val taskId = target.removePrefix("separate:")
    val preparedCopy = target.startsWith("separate:")
    val task = state.tasks.find { it.id == taskId }
    val destination = when (target) {
        "draft" -> session.draft.destination
        "settings" -> state.settings.defaultDestination
        else -> task?.destination ?: state.settings.defaultDestination
    }
    ScreenBody {
        PageIntro("Where should it go?", if (preparedCopy) "The previous save attempt is retained in history. Choose a location for the separate copy, then tap Retry save. No new document is created until you continue."
            else "Choose a folder you can find again. Android manages access to the location you select.")
        ContentCard {
            KeyValue("Current location", destination.displayName)
            if (!destination.available) InlineError("Access to this location is no longer available. Choose it again or use another folder.")
            KeyValue("Available space", destination.freeBytes?.let(::formatBytes) ?: "The provider has not reported available space")
        }
        if (Build.VERSION.SDK_INT >= 29) SettingsRow(Icons.Outlined.Download, "Downloads", "The shared Downloads folder on this device",
            { model.destinationChosen(target, UiDestination()) })
        SettingsRow(Icons.Outlined.FolderOpen, "Choose a folder", "Use Android's folder picker", { model.pickDestination(target) })
        if (preparedCopy && task != null) Button({ model.taskAction(task.id, TaskOperation.RETRY_SAVE) }, Modifier.fillMaxWidth().heightIn(min = 54.dp),
            enabled = !session.busy && TaskOperation.RETRY_SAVE in task.permittedActions) { Text("Retry save") }
        InfoBanner("Downloaded data stays safe", "If the destination becomes unavailable, the downloaded data is kept so you can retry saving. A cloud folder may use its provider's own network connection.")
        Text("Canceling the Android picker leaves the current location unchanged.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CredentialsScreen(state: BmdAppState, session: BmdSession, model: BmdViewModel) {
    val settings = session.route.context == "settings"
    val origin = session.credentialOrigin.ifBlank { session.route.id?.let { id -> state.tasks.find { it.id == id }?.origin } ?: parseSourceOrigin(session.draft.url).orEmpty() }
    val form = session.credentialForm ?: UiCredentialInput(origin, CredentialKind.NONE)
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(origin) { if (session.credentialForm == null) model.updateCredentialForm(form) }
    ScreenBody(Modifier.imePadding()) {
        PageIntro("Authenticate with the source", "Black Marlin has no account login. This credential is sent only to the exact HTTPS origin you choose.")
        ContentCard {
            OutlinedTextField(form.origin, { model.updateCredentialForm(form.copy(origin = it, secret = "")) }, Modifier.fillMaxWidth(),
                label = { Text("Exact HTTPS origin") }, placeholder = { Text("https://files.example.com:8443") },
                readOnly = !settings, singleLine = false, maxLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            Text("Scheme, host and port must match. Subdomains and redirected origins do not inherit this credential.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceRow("None", null, form.kind == CredentialKind.NONE, { model.updateCredentialForm(form.copy(kind = CredentialKind.NONE, secret = "", username = "")) })
            ChoiceRow("HTTP Basic", "A username and password supplied by this source", form.kind == CredentialKind.BASIC,
                { model.updateCredentialForm(form.copy(kind = CredentialKind.BASIC, secret = "")) })
            ChoiceRow("Bearer token", "A token supplied by this source", form.kind == CredentialKind.BEARER,
                { model.updateCredentialForm(form.copy(kind = CredentialKind.BEARER, secret = "", username = "")) })
            if (form.kind == CredentialKind.BASIC) OutlinedTextField(form.username, { model.updateCredentialForm(form.copy(username = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Source username") }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            if (form.kind != CredentialKind.NONE) {
                OutlinedTextField(form.secret, { model.updateCredentialForm(form.copy(secret = it)) }, Modifier.fillMaxWidth(),
                    label = { Text(if (form.kind == CredentialKind.BASIC) "Source password" else "Bearer token") },
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = { IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (reveal) "Hide credential" else "Show credential") } })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(form.rememberForOrigin, { model.updateCredentialForm(form.copy(rememberForOrigin = it)) })
                    Column(Modifier.weight(1f)) {
                        Text("Remember for this origin", style = MaterialTheme.typography.bodyLarge)
                        Text("Reuse an encrypted credential for future downloads until you choose Forget.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!settings) InfoBanner("For this download", "Without Remember, the encrypted credential is kept only while this task needs it, including after an interruption, and removed after saving or cancellation cleanup.")
        Button({ model.saveCredential(form) }, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = !session.busy && form.origin.isNotBlank() &&
            (form.kind == CredentialKind.NONE || form.secret.isNotBlank()) && (!settings || form.kind == CredentialKind.NONE || form.rememberForOrigin)) {
            Text(if (settings) "Save source preference" else "Use for this source")
        }
        if (state.credentials.any { it.origin == form.origin }) TextButton({ model.requestForgetCredential(form.origin) }, Modifier.align(Alignment.CenterHorizontally)) {
            Text("Forget remembered credential", color = MaterialTheme.colorScheme.error)
        }
    }
}
