package com.blackmarlin.downloader.ui

import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

val TransferStage.label: String
    get() = when (this) {
        TransferStage.QUEUED -> "Queued"
        TransferStage.CONNECTING -> "Connecting"
        TransferStage.DOWNLOADING -> "Downloading"
        TransferStage.PAUSING -> "Pausing"
        TransferStage.PAUSED -> "Paused by you"
        TransferStage.WAITING_NETWORK -> "Waiting for network"
        TransferStage.WAITING_SPACE -> "Waiting for space"
        TransferStage.WAITING_ANDROID -> "Waiting for Android"
        TransferStage.INTERRUPTED -> "Interrupted"
        TransferStage.VERIFYING -> "Verifying"
        TransferStage.WAITING_VERIFY -> "Waiting to verify"
        TransferStage.SAVING -> "Saving"
        TransferStage.WAITING_SAVE -> "Waiting to save"
        TransferStage.NEEDS_LINK -> "Needs a new link"
        TransferStage.NEEDS_CREDENTIALS -> "Needs source sign-in"
        TransferStage.FILE_CHANGED -> "File changed"
        TransferStage.FAILED -> "Needs attention"
        TransferStage.COMPLETE -> "Complete"
        TransferStage.CANCELING -> "Canceling"
        TransferStage.CANCELED -> "Canceled"
    }

val TransferStage.isActive: Boolean
    get() = this in setOf(TransferStage.QUEUED, TransferStage.CONNECTING, TransferStage.DOWNLOADING,
        TransferStage.PAUSING, TransferStage.VERIFYING, TransferStage.SAVING, TransferStage.CANCELING)

val TransferStage.needsAttention: Boolean
    get() = this in setOf(TransferStage.WAITING_SPACE, TransferStage.WAITING_ANDROID,
        TransferStage.INTERRUPTED, TransferStage.WAITING_VERIFY, TransferStage.WAITING_SAVE,
        TransferStage.NEEDS_LINK, TransferStage.NEEDS_CREDENTIALS, TransferStage.FILE_CHANGED, TransferStage.FAILED)

val UiTask.progressFraction: Float?
    get() {
        if (stage == TransferStage.COMPLETE) return 1f
        val total = if (stage == TransferStage.SAVING) saveTotalBytes else totalBytes
        val received = if (stage == TransferStage.SAVING) saveBytes else bytesReceived
        return if (total != null && total > 0 && received != null) {
            (received.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else null
    }

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "Unknown"
    if (bytes < 1024) return "$bytes B"
    var amount = bytes.toDouble()
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var unit = -1
    do { amount /= 1024.0; unit++ } while (amount >= 1024 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), if (amount >= 100) "%.0f %s" else "%.1f %s", amount, units[unit])
}

fun formatDuration(seconds: Long?): String? = when {
    seconds == null || seconds < 0 -> null
    seconds < 60 -> "Less than a minute left"
    seconds < 3600 -> "${(seconds + 59) / 60} min left"
    seconds < 86400 -> "${seconds / 3600} hr ${(seconds % 3600) / 60} min left"
    else -> "${seconds / 86400} days left"
}

fun formatDate(timestamp: Long): String = if (timestamp <= 0) "" else
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

fun UiTask.progressDescription(): String {
    val progress = progressFraction
    val completedBytes = if (stage == TransferStage.SAVING) saveBytes else bytesReceived
    val expectedBytes = if (stage == TransferStage.SAVING) saveTotalBytes else totalBytes
    return buildString {
        append(stage.label)
        if (progress != null && stage != TransferStage.COMPLETE) append(", ${(progress * 100).roundToInt()} percent")
        append(", ${formatBytes(completedBytes)}")
        expectedBytes?.let { append(" of ${formatBytes(it)}") }
    }
}

fun UiFile.kind(): FileKind {
    val mime = mimeType.lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> FileKind.IMAGES
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") || mime.contains("7z") -> FileKind.ARCHIVES
        mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") || mime.contains("sheet") || mime.contains("presentation") ||
            mime.contains("msword") || mime.contains("ms-excel") || mime.contains("ms-powerpoint") || mime.contains("rtf") || mime.contains("epub") -> FileKind.DOCUMENTS
        else -> FileKind.OTHER
    }
}

/** UI preflight only. The coordinator independently validates before opening a socket. */
fun parseSourceOrigin(input: String): String? = runCatching {
    val uri = URI(input.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (!uri.rawUserInfo.isNullOrEmpty() || uri.host.isNullOrBlank()) return null
    if (uri.port < -1 || uri.port > 65535 || uri.port == 0) return null
    val host = uri.host.lowercase(Locale.ROOT)
    val displayHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
    val defaultPort = if (scheme == "https") 443 else 80
    val port = if (uri.port > 0 && uri.port != defaultPort) ":${uri.port}" else ""
    "$scheme://$displayHost$port"
}.getOrNull()

fun isValidExpectedSha256(value: String): Boolean = value.isBlank() || value.trim().matches(Regex("[a-fA-F0-9]{64}"))

const val MAX_SHARED_TEXT_CHARS = 16 * 1024
const val MAX_SHARED_LINKS = 50

fun boundSharedText(value: CharSequence?): String? {
    if (value == null) return null
    if (value.length <= MAX_SHARED_TEXT_CHARS) return value.toString()
    var end = MAX_SHARED_TEXT_CHARS
    if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
    return value.subSequence(0, end).toString()
}

fun sharedHttpLinks(value: String): List<String> {
    val bounded = boundSharedText(value).orEmpty()
    val exact = bounded.trim()
    if (parseSourceOrigin(exact) != null && exact.none(Char::isWhitespace)) return listOf(exact)
    return Regex("(?i)https?://[^\\s<>\\\"']+").findAll(bounded).map { match ->
        var link = match.value.trimEnd('.', ',', ';')
        while (link.endsWith(')') && link.count { it == ')' } > link.count { it == '(' }) link = link.dropLast(1)
        while (link.endsWith(']') && link.count { it == ']' } > link.count { it == '[' }) link = link.dropLast(1)
        link
    }.filter { parseSourceOrigin(it) != null }.distinct().take(MAX_SHARED_LINKS).toList()
}

/** The one link a clipboard text holds, or null: several links are ambiguous, so none is picked for the user. */
fun clipboardHttpLink(value: CharSequence?): String? = sharedHttpLinks(boundSharedText(value).orEmpty()).singleOrNull()

fun selectedNavigationTab(route: BmdRoute): BmdScreen = when {
    route.screen == BmdScreen.FILES || route.screen == BmdScreen.FILE_DETAIL -> BmdScreen.FILES
    route.screen in setOf(BmdScreen.TRANSFERS, BmdScreen.TRANSFER_DETAIL, BmdScreen.ADD_LINK, BmdScreen.REVIEW, BmdScreen.SEARCH) -> BmdScreen.TRANSFERS
    route.screen in setOf(BmdScreen.DESTINATION, BmdScreen.CREDENTIALS) && route.context != "settings" -> BmdScreen.TRANSFERS
    else -> BmdScreen.SETTINGS
}
