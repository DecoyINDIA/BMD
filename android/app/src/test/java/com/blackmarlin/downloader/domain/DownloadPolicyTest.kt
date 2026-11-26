package com.blackmarlin.downloader.domain

import com.blackmarlin.downloader.ui.BmdUiException
import com.blackmarlin.downloader.ui.CredentialKind
import com.blackmarlin.downloader.ui.UiCredentialInput
import org.junit.Assert.*
import org.junit.Test

class DownloadPolicyTest {
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected policy rejection") } catch (_: BmdUiException) { }
    }
    @Test fun cleartextRequiresConsentBeforeARequestCanBeConstructed() {
        rejected { DownloadPolicy.parse("http://example.org/file", false) }
        assertFalse(DownloadPolicy.parse("http://example.org/file", true).secure)
        rejected { DownloadPolicy.parse("file:///data/secret", true) }
        rejected { DownloadPolicy.parse("ftp://example.org/file", true) }
    }
    @Test fun userInfoControlsAndNumericIpAliasesCannotDisagreeAcrossJvmAndRust() {
        listOf("https://name:secret@example.org/file", "https://example.org/a\nb", "https://2130706433/file",
            "https://127.1/file", "https://0177.0.0.1/file", "https://0x7f000001/file", "https://[fe80::1%25eth0]/file")
            .forEach { rejected { DownloadPolicy.parse(it, false) } }
        assertEquals("http://127.0.0.1:8080", DownloadPolicy.parse("http://127.0.0.1:8080/file", true).origin)
    }
    @Test fun credentialScopeIncludesSchemeAndNonDefaultPort() {
        val source = DownloadPolicy.parse("https://example.org:8443/a?signature=private", false)
        assertEquals("https://example.org:8443", source.origin)
        rejected { DownloadPolicy.validateCredential(source, UiCredentialInput("https://example.org", CredentialKind.BEARER, secret = "token")) }
        DownloadPolicy.validateCredential(source, UiCredentialInput("https://example.org:8443", CredentialKind.BEARER, secret = "token"))
        rejected { DownloadPolicy.validateCredential(DownloadPolicy.parse("http://example.org/a", true), UiCredentialInput("https://example.org", CredentialKind.BASIC, "user", "password")) }
        assertEquals("https://example.org", DownloadPolicy.parse("HTTPS://EXAMPLE.ORG:443/path", false).origin)
    }
    @Test fun credentialHeadersRejectInjectionAndAmbiguousBasicUsernames() {
        val source = DownloadPolicy.parse("https://example.org", false)
        rejected { DownloadPolicy.validateCredential(source, UiCredentialInput(source.origin, CredentialKind.BEARER, secret = "token\r\nX-Header: value")) }
        rejected { DownloadPolicy.validateCredential(source, UiCredentialInput(source.origin, CredentialKind.BASIC, "user:name", "pass")) }
        rejected { DownloadPolicy.validateCredential(source, UiCredentialInput(source.origin, CredentialKind.BEARER, secret = "bad\u0000token")) }
    }
    @Test fun namesRemainSingleUtf8BoundedComponents() {
        val name = DownloadPolicy.filename("../folder\\unsafe\u0000name.pdf")
        assertFalse(name.contains('/')); assertFalse(name.contains('\\')); assertFalse(name.contains('\u0000'))
        assertEquals("download", DownloadPolicy.filename(".."))
        val unicode = DownloadPolicy.filename("😀".repeat(200))
        assertTrue(unicode.toByteArray(Charsets.UTF_8).size <= 180)
        assertFalse(unicode.last().isHighSurrogate())
    }
    @Test fun completeSourceIdentityIsPreservedButNeverPrinted() {
        val url = "https://example.org/f?signature=not-public#fragment"
        val source = DownloadPolicy.parse(url, false)
        assertEquals(url, source.url)
        assertFalse(source.toString().contains("not-public"))
        rejected { DownloadPolicy.parse("https://example.org/" + "a".repeat(8192), false) }
    }
    @Test fun checksumsAreUnambiguous() {
        assertNull(DownloadPolicy.expectedHash("  "))
        assertEquals("a".repeat(64), DownloadPolicy.expectedHash("A".repeat(64)))
        rejected { DownloadPolicy.expectedHash("0".repeat(63)) }
        rejected { DownloadPolicy.expectedHash("g".repeat(64)) }
    }
    @Test fun signedPathsAreNeverSilentlyNormalizedAndIpv6OriginsAgreeWithRust() {
        for (path in listOf("/a/../file", "/./file", "/a/%2e%2e/file", "/a/.%2E/file", "/a/%2e./file")) {
            rejected { DownloadPolicy.parse("https://example.org$path?token=A%2bB&x=1&x=2", false) }
        }
        val url = "https://example.org/a%2Fb/file?token=A%2bB&x=1&x=2&dot=../"
        assertEquals(url, DownloadPolicy.parse(url, false).url)
        assertEquals("https://[2001:db8::1]:8443", DownloadPolicy.parse("https://[2001:0db8:0:0:0:0:0:1]:8443/file", false).origin)
        assertEquals("https://[::]", DownloadPolicy.parse("https://[0:0:0:0:0:0:0:0]/", false).origin)
        assertEquals("https://[::ffff:c000:201]", DownloadPolicy.parse("https://[::ffff:192.0.2.1]/", false).origin)
    }
    @Test fun temporaryStorageBudgetIncludesCopyAndCannotOverflow() {
        val gib = 1024L * 1024 * 1024
        assertEquals(8 * gib + StorageBudget.SAFETY_BYTES, StorageBudget.requiredFreeBytes(4 * gib))
        assertEquals(6 * gib + StorageBudget.SAFETY_BYTES, StorageBudget.requiredFreeBytes(4 * gib, 2 * gib))
        assertEquals(2 * gib + StorageBudget.SAFETY_BYTES, StorageBudget.requiredFreeBytes(4 * gib, 2 * gib, false))
        assertEquals(StorageBudget.SAFETY_BYTES, StorageBudget.requiredFreeBytes(null))
        assertEquals(Long.MAX_VALUE, StorageBudget.requiredFreeBytes(Long.MAX_VALUE))
    }
    @Test fun cleanupNeverDiscardsAnUnresolvedOutputOrLiveWriter() {
        for (phase in listOf("CREATE_PLANNED", "URI_RECORDED", "COPYING", "COPY_COMPLETE", "COMMIT_PLANNED", "PROVIDER_CLOSED", "UNRESOLVED")) {
            assertFalse(PublicationRules.mayCleanStage(phase, true))
            assertEquals("RECONCILE_AND_RETAIN", PublicationRules.cancelDisposition(phase))
        }
        assertFalse(PublicationRules.mayCleanStage(null, false))
        assertTrue(PublicationRules.mayCleanStage("PUBLISHED", true))
        assertEquals("PRESERVE_SAVED", PublicationRules.cancelDisposition("PUBLISHED"))
    }
}
