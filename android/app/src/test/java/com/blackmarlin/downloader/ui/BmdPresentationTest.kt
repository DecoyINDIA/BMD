package com.blackmarlin.downloader.ui

import org.junit.Assert.*
import org.junit.Test

class BmdPresentationTest {
    @Test fun exactOriginNormalizesSchemeAndHostButRetainsNondefaultPort() {
        assertEquals("https://example.com", parseSourceOrigin("HTTPS://EXAMPLE.COM:443/file?q=1"))
        assertEquals("https://example.com:8443", parseSourceOrigin("https://example.com:8443/file"))
        assertEquals("http://192.168.1.2:8080", parseSourceOrigin("http://192.168.1.2:8080/file"))
        assertNull(parseSourceOrigin("https://user:pass@example.com/file"))
        assertNull(parseSourceOrigin("file:///private/path"))
    }

    @Test fun unknownLengthDoesNotInventAPercentage() {
        val task = UiTask("id", "file", "https://example.com", TransferStage.DOWNLOADING, bytesReceived = 900)
        assertNull(task.progressFraction)
        assertFalse(task.progressDescription().contains("percent"))
    }

    @Test fun savingUsesRealCopyProgressSeparatelyFromNetworkBytes() {
        val task = UiTask("id", "file", "https://example.com", TransferStage.SAVING,
            bytesReceived = 1_000, totalBytes = 1_000, saveBytes = 250, saveTotalBytes = 1_000)
        assertEquals(.25f, task.progressFraction!!, .0001f)
    }

    @Test fun largeFilesKeep64BitProgress() {
        val total = 12L * 1024 * 1024 * 1024
        val task = UiTask("id", "file", "https://example.com", TransferStage.DOWNLOADING, bytesReceived = total / 2, totalBytes = total)
        assertEquals(.5f, task.progressFraction!!, .0001f)
        assertTrue(formatBytes(total).contains("GiB"))
    }

    @Test fun expectedHashRequiresExactly64HexCharacters() {
        assertTrue(isValidExpectedSha256(""))
        assertTrue(isValidExpectedSha256("aB09".repeat(16)))
        assertFalse(isValidExpectedSha256("a".repeat(63)))
        assertFalse(isValidExpectedSha256("z".repeat(64)))
    }

    @Test fun sharingPreservesIpv6BracketsAndLiteralUrlPunctuation() {
        assertEquals(listOf("http://[::1]"), sharedHttpLinks("http://[::1]"))
        assertEquals(listOf("https://example.com/file."), sharedHttpLinks("https://example.com/file."))
        assertEquals(listOf("https://example.com/archive.zip"), sharedHttpLinks("Download (https://example.com/archive.zip)."))
    }
}
