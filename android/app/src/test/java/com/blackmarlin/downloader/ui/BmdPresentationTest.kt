package com.blackmarlin.downloader.ui

import com.blackmarlin.downloader.data.permittedTaskActions
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

    @Test fun sharedTextScanningIsBoundedAndPreservesSurrogatePairs() {
        val oversized = "x".repeat(MAX_SHARED_TEXT_CHARS + 5_000) + " https://ignored.example.test/file.zip"
        assertEquals(emptyList<String>(), sharedHttpLinks(oversized))
        val prefixLink = "Note https://kept.example.test/file.zip " + "x".repeat(MAX_SHARED_TEXT_CHARS + 5_000)
        assertEquals(listOf("https://kept.example.test/file.zip"), sharedHttpLinks(prefixLink))
        val surrogateBoundary = "a".repeat(MAX_SHARED_TEXT_CHARS - 1) + "\uD83D\uDE00"
        val bounded = boundSharedText(surrogateBoundary)!!
        assertEquals(MAX_SHARED_TEXT_CHARS - 1, bounded.length)
        assertFalse(bounded.last().isHighSurrogate())
    }

    @Test fun destinationAndCredentialsHighlightTheirOriginatingTab() {
        assertEquals(BmdScreen.TRANSFERS, selectedNavigationTab(BmdRoute(BmdScreen.DESTINATION, context = "draft")))
        assertEquals(BmdScreen.TRANSFERS, selectedNavigationTab(BmdRoute(BmdScreen.DESTINATION, context = "task-1")))
        assertEquals(BmdScreen.TRANSFERS, selectedNavigationTab(BmdRoute(BmdScreen.DESTINATION, context = "separate:task-1")))
        assertEquals(BmdScreen.SETTINGS, selectedNavigationTab(BmdRoute(BmdScreen.DESTINATION, context = "settings")))
        assertEquals(BmdScreen.TRANSFERS, selectedNavigationTab(BmdRoute(BmdScreen.CREDENTIALS, context = "source")))
        assertEquals(BmdScreen.SETTINGS, selectedNavigationTab(BmdRoute(BmdScreen.CREDENTIALS, context = "settings")))
    }

    @Test fun failedAndFileChangedStagesExposeWorkingRecoveryActions() {
        val failed = permittedTaskActions(TransferStage.FAILED, null, "RUN", local = false, verified = false, hasPublication = false)
        assertTrue(TaskOperation.RESUME in failed)
        assertTrue(TaskOperation.REPLACE_LINK in failed)
        assertTrue(TaskOperation.CANCEL in failed)

        val changed = permittedTaskActions(TransferStage.FILE_CHANGED, null, "RUN", local = false, verified = false, hasPublication = false)
        assertTrue(TaskOperation.RESTART in changed)
        assertTrue(TaskOperation.REPLACE_LINK in changed)
        assertTrue(TaskOperation.CANCEL in changed)

        val waitingAndroid = permittedTaskActions(TransferStage.WAITING_ANDROID, null, "RUN", local = false, verified = false, hasPublication = false)
        assertTrue(TaskOperation.RESUME in waitingAndroid)
        assertTrue(TaskOperation.CANCEL in waitingAndroid)
    }
}
