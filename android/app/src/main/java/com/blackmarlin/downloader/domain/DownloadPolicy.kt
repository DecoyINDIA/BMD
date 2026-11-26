package com.blackmarlin.downloader.domain

import com.blackmarlin.downloader.ui.*
import java.net.IDN
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

data class ParsedSource(val url: String, val origin: String, val host: String, val secure: Boolean) {
    override fun toString() = "ParsedSource(origin=$origin)"
}

/** Applied again at persistence/admission; a UI preview is never an authorization token. */
object DownloadPolicy {
    fun parse(raw: String, allowPlainHttp: Boolean): ParsedSource {
        val value = raw.trim()
        requireSafe(value.length in 1..8192 && value.toByteArray(Charsets.UTF_8).size <= 8192, "Enter one complete HTTP or HTTPS link of at most 8 KiB.")
        requireSafe(value.none { it.code < 32 || it.code == 127 || it.isWhitespace() }, "The link contains spaces or control characters.")
        val uri = runCatching { URI(value) }.getOrElse { throw BmdUiException("That link is not valid.") }
        requireSafe(uri.rawPath.orEmpty().split('/').none { it.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".") in setOf(".", "..") }, "This link contains a dot path segment that would change during URL parsing. Use a direct link without dot segments.")
        val scheme = uri.scheme?.lowercase()
        requireSafe(scheme == "https" || scheme == "http", "Only direct HTTP and HTTPS links are supported.")
        requireSafe(uri.rawUserInfo == null, "Remove credentials from the link and use Source credentials.")
        requireSafe(!uri.isOpaque && !uri.rawAuthority.isNullOrEmpty(), "Enter a link with a valid server name.")
        val rawHost = uri.host ?: throw BmdUiException("Enter a valid server name. International names must use their ASCII form.")
        requireSafe('%' !in rawHost, "Scoped IPv6 addresses are not supported in download links.")
        val host = if (rawHost.startsWith("[")) canonicalIpv6(rawHost) else runCatching { IDN.toASCII(rawHost).lowercase() }
            .getOrElse { throw BmdUiException("That server name is not valid.") }
        requireSafe(host.isNotBlank() && uri.port in -1..65535 && uri.port != 0, "That server address or port is not valid.")
        if (host.matches(Regex("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*\\.?"))) {
            val parts = host.split('.')
            requireSafe(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: 256) <= 255 }, "Use a standard four-part decimal IP address.")
        }
        requireSafe('%' !in host, "Scoped IPv6 addresses are not supported in download links.")
        val secure = scheme == "https"
        requireSafe(secure || allowPlainHttp, "This link uses unencrypted HTTP. Confirm it before any request is sent.")
        val port = uri.port.takeUnless { it == -1 || (secure && it == 443) || (!secure && it == 80) }
        return ParsedSource(value, "$scheme://$host${port?.let { ":$it" } ?: ""}", host, secure)
    }

    fun filename(raw: String): String {
        requireSafe(raw.length <= 4096, "The file name is too long.")
        var name = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .map { if (it.code < 32 || it.code in 127..159 || it in "/\\:*?\"<>|") '_' else it }
            .joinToString("").trim().trim('.')
        if (name.isBlank() || name == "." || name == "..") name = "download"
        while (name.toByteArray(StandardCharsets.UTF_8).size > 180) name = name.dropLast(Character.charCount(name.codePointBefore(name.length)))
        return name
    }

    fun expectedHash(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        requireSafe(value.matches(Regex("[a-fA-F0-9]{64}")), "SHA-256 must contain exactly 64 hexadecimal characters.")
        return value.lowercase()
    }

    fun validateCredential(source: ParsedSource, credential: UiCredentialInput) {
        requireSafe(source.secure, "Source credentials require HTTPS.")
        val origin = parse(credential.origin, false).origin
        requireSafe(origin == source.origin, "These credentials belong to a different HTTPS origin.")
        requireSafe(credential.secret.length <= 6000 && credential.username.length <= 1000, "The source credential is too long.")
        requireSafe(credential.username.none { it == ':' || it.code < 32 || it.code == 127 }, "The username contains unsupported characters.")
        requireSafe(credential.secret.none { it == '\r' || it == '\n' || it.code == 0 || (credential.kind == CredentialKind.BEARER && (it.code < 32 || it.code == 127)) }, "The credential contains unsupported characters.")
    }

    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun isFinal(stage: String) = stage == TransferStage.COMPLETE.name || stage == TransferStage.CANCELED.name
    fun requireSafe(test: Boolean, message: String) { if (!test) throw BmdUiException(message) }

    /** URL-standard IPv6 serialization using integer parsing only; never invokes DNS. */
    private fun canonicalIpv6(bracketed: String): String {
        var literal = bracketed.removePrefix("[").removeSuffix("]").lowercase()
        if ('.' in literal) {
            val tail = literal.substringAfterLast(':').split('.')
            requireSafe(tail.size == 4 && tail.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && (it.toIntOrNull() ?: 256) <= 255 }, "That IPv6 address is not valid.")
            val octets = tail.map(String::toInt)
            literal = literal.substringBeforeLast(':') + ":" + ((octets[0] shl 8) or octets[1]).toString(16) + ":" + ((octets[2] shl 8) or octets[3]).toString(16)
        }
        val sides = literal.split("::")
        requireSafe(sides.size in 1..2, "That IPv6 address is not valid.")
        fun groups(value: String) = if (value.isEmpty()) emptyList() else value.split(':').map {
            requireSafe(it.length in 1..4 && it.all { char -> char in '0'..'9' || char in 'a'..'f' }, "That IPv6 address is not valid.")
            it.toInt(16)
        }
        val left = groups(sides[0]); val right = if (sides.size == 2) groups(sides[1]) else emptyList()
        val count = left.size + right.size
        requireSafe(if (sides.size == 1) count == 8 else count < 8, "That IPv6 address is not valid.")
        val values = if (sides.size == 1) left else left + List(8 - count) { 0 } + right
        var bestStart = -1; var bestLength = 0; var index = 0
        while (index < values.size) {
            if (values[index] != 0) { index++; continue }
            val start = index
            while (index < values.size && values[index] == 0) index++
            if (index - start > bestLength && index - start >= 2) { bestStart = start; bestLength = index - start }
        }
        val serialized = if (bestStart < 0) values.joinToString(":") { it.toString(16) }
            else values.take(bestStart).joinToString(":") { it.toString(16) } + "::" + values.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return "[$serialized]"
    }
}

/** Provider publication is irreversible until a separate explicit Delete action. */
object PublicationRules {
    val uncertainPhases = setOf("CREATE_PLANNED", "COMMIT_PLANNED", "PROVIDER_CLOSED", "VISIBLE", "UNRESOLVED")
    fun mayCleanStage(phase: String?, writerReleased: Boolean): Boolean = writerReleased && (phase == null || phase == "PUBLISHED" || phase == "CLEANED")
    fun cancelDisposition(phase: String?): String = when (phase) {
        "PUBLISHED", "CLEANED" -> "PRESERVE_SAVED"
        null -> "STOP_THEN_CLEAN"
        else -> "RECONCILE_AND_RETAIN"
    }
}
