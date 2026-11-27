package com.blackmarlin.downloader.widget

import com.blackmarlin.downloader.ui.TransferStage
import com.blackmarlin.downloader.ui.UiTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickDownloadProgressTest {
    @Test
    fun progressKeepsMovingForwardThroughPublication() {
        val task = UiTask("transfer", "file.zip", "https://example.org", TransferStage.DOWNLOADING,
            bytesReceived = 100, totalBytes = 100, saveBytes = 0, saveTotalBytes = 100)
        val verifying = task.copy(stage = TransferStage.VERIFYING)
        val saving = task.copy(stage = TransferStage.SAVING)
        val saved = saving.copy(saveBytes = 100)
        val complete = saved.copy(stage = TransferStage.COMPLETE)

        assertNull(task.copy(totalBytes = null).widgetProgress)
        assertEquals(.95f, task.widgetProgress!!, .001f)
        assertEquals(task.widgetProgress, verifying.widgetProgress)
        assertEquals(verifying.widgetProgress, saving.widgetProgress)
        assertEquals(.99f, saved.widgetProgress!!, .001f)
        assertTrue(saved.showsWidgetProgress)
        assertEquals(1f, complete.widgetProgress!!, .001f)
        assertFalse(complete.showsWidgetProgress)
    }
}
