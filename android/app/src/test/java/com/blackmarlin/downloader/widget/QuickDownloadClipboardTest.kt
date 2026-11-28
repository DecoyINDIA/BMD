package com.blackmarlin.downloader.widget

import com.blackmarlin.downloader.ui.clipboardHttpLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickDownloadClipboardTest {
    private val link = "https://example.org/archive.zip"

    @Test
    fun aCopiedLinkFillsAnEmptyBoxAndCountsAsEdited() {
        val filled = QuickDownloadForm().withClipboardLink(link)
        assertEquals(link, filled.url)
        assertTrue(filled.edited)
    }

    @Test
    fun aLinkInsideSentenceIsFound() {
        assertEquals(link, QuickDownloadForm().withClipboardLink("Get it here: ($link).").url)
    }

    @Test
    fun textWithoutALinkOrWithSeveralIsIgnored() {
        val empty = QuickDownloadForm()
        assertSame(empty, empty.withClipboardLink("just some words"))
        assertSame(empty, empty.withClipboardLink("ftp://example.org/a.zip"))
        assertSame(empty, empty.withClipboardLink(null))
        assertSame(empty, empty.withClipboardLink("$link and https://example.org/other.zip"))
    }

    @Test
    fun typedClearedOrBusyInputIsNeverOverwritten() {
        for (form in listOf(
            QuickDownloadForm(url = "https://typed.example", edited = true),
            QuickDownloadForm(url = "", edited = true),
            QuickDownloadForm(url = "https://typed.example"),
            QuickDownloadForm(busy = true),
        )) assertSame(form, form.withClipboardLink(link))
    }

    @Test
    fun anOldErrorDoesNotBlockAutofillButIsReplaced() {
        val filled = QuickDownloadForm(error = "old").withClipboardLink(link)
        assertEquals(link, filled.url)
        assertNull(filled.error)
    }

    @Test
    fun oversizedClipboardIsBounded() {
        assertNull(clipboardHttpLink("x".repeat(40_000) + " $link"))
    }
}
