package com.blackmarlin.downloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.blackmarlin.downloader.BmdApplication
import com.blackmarlin.downloader.MainActivity
import com.blackmarlin.downloader.platform.TaskScheduler
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Runs against the actual app, Room database, Rust JNI library and Android scheduler. */
@RunWith(AndroidJUnit4::class)
class NativeAppSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    /**
     * MainActivity correctly retains a real SEND intent. ActivityScenario instead identifies
     * lifecycle callbacks by the original launch intent and ignores subsequent callbacks after
     * onNewIntent/setIntent. Restore only the test rule's launch identity after all assertions,
     * so the outer rule can observe actual destruction. No production intent handling changes.
     * Capture the primary failure before ActivityScenario cleanup can replace its stack trace.
     */
    @get:Rule val rules: TestRule = RuleChain.outerRule(compose).around(TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val activity = compose.activity
                val launchIntent = Intent(activity.intent)
                var primaryFailure: Throwable? = null
                try {
                    base.evaluate()
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    Log.e("BmdDeviceTest", "Primary test failure: ${description.methodName}", failure)
                    runCatching { recordDeviceFailure(UiDevice.getInstance(instrumentation), description.methodName, failure) }
                    throw failure
                } finally {
                    val cleanupFailure = runCatching {
                        instrumentation.runOnMainSync {
                            if (!activity.isDestroyed) activity.intent = launchIntent
                        }
                    }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure)
                        else throw cleanupFailure
                    }
                }
            }
        }
    })
    private val graph get() = (compose.activity.application as BmdApplication).graph

    @Test fun realDatabaseAndNativeEngineInitialize() {
        runBlocking {
            withTimeout(30_000) {
                graph.ensureReady()
                graph.engine.initialize() // Loads and calls the packaged JNI engine; no test replacement.
                graph.db.tasks().tasks()
            }
        }
        compose.waitUntil(15_000) { !graph.repository.state.value.loading }
        val workConfiguration = WorkManager.getInstance(compose.activity).configuration
        assertEquals(TaskScheduler.FIRST_WORK_JOB_ID, workConfiguration.minJobSchedulerId)
        assertEquals(TaskScheduler.LAST_WORK_JOB_ID, workConfiguration.maxJobSchedulerId)
        assertTrue("WorkManager and user-initiated transfers must use disjoint JobScheduler IDs",
            workConfiguration.minJobSchedulerId > TaskScheduler.LAST_TRANSFER_JOB_ID)
        compose.onNodeWithTag("screen-transfers").assertExists()
    }

    /** Exercises JNI plus Android platform certificate verification; this is a metadata probe. */
    @Test fun pinnedHttpsSourceUsesAndroidPlatformTrust() {
        val origin = "https://raw.githubusercontent.com"
        val pinnedUrl = "$origin/DecoyINDIA/BMD/6b4038a7691bea096d7f035475ebc277db23c11a/LICENSE"
        val review = runBlocking {
            withTimeout(90_000) {
                graph.ensureReady()
                graph.repository.inspectLink(InspectLinkRequest(
                    url = pinnedUrl,
                    networkPolicy = NetworkPolicy.ANY,
                    allowPlainHttp = false,
                    credential = UiCredentialInput(origin, CredentialKind.NONE),
                ))
            }
        }
        assertEquals("The pinned HTTPS resource must be probed through the actual native backend", pinnedUrl, review.url)
        assertEquals(origin, review.origin)
        assertEquals("The pinned Git blob has 35,149 bytes; offline/failed metadata cannot satisfy the TLS gate",
            35_149L, review.totalBytes)
        assertFalse("The public pinned fixture must not require source authentication", review.requiresCredentials)
        assertFalse("The source must be the pinned license file, not an HTML error page", review.possibleHtmlPage)
        assertNull("Offline or warning fallback cannot satisfy the real HTTPS gate", review.warning)
    }

    @Test fun nativeNavigationPersistsAppearanceThroughRealRepository() {
        compose.waitUntil(15_000) { !graph.repository.state.value.loading }
        val before = graph.repository.state.value.settings
        try {
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Appearance").performScrollTo().performClick()
            compose.onNodeWithText("Dark", substring = false).performClick()
            compose.waitUntil(10_000) { graph.repository.state.value.settings.theme == ThemeMode.DARK }
            // A Material snackbar can cover a row scrolled to the bottom of the screen.
            // Observe the real persistence acknowledgement, then wait for it to leave.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Settings saved.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Settings saved.", substring = true).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Network and performance").performScrollTo().performClick()
            compose.onNodeWithText("Default network").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Help and diagnostics").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("screen-help").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("What links are supported?").performScrollTo().performClick()
            compose.onNodeWithText("Use a direct HTTP or HTTPS link", substring = true).assertExists()
        } finally {
            runBlocking { graph.repository.execute(BmdCommand.UpdateSettings(before)) }
        }
    }

    @Test fun sharedUnsupportedLinkStaysInEditorAndNeverCreatesATask() {
        compose.waitUntil(15_000) { !graph.repository.state.value.loading }
        val before = runBlocking { graph.db.tasks().tasks().map { it.id }.toSet() }
        compose.waitUntil(10_000) { graph.repository.state.value.tasks.map { it.id }.toSet() == before }
        val unsupportedUrl = "ftp://example.invalid/archive.zip"
        share(unsupportedUrl)
        // The real SEND entry rejects unsupported schemes before Review is requested.
        // Do not rewrite the same URL: that opens the IME and obscures the button below it.
        compose.waitUntil(10_000) { hasText("Share a direct HTTP or HTTPS file link.") }
        compose.onNodeWithTag("screen-add_link").assertExists()
        compose.onNodeWithTag("download-url").assertTextContains(unsupportedUrl)
        assertEquals(before, graph.repository.state.value.tasks.map { it.id }.toSet())

        // Explicit Review remains a separate validation path with its own user-facing copy.
        compose.onNodeWithTag("download-review").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Enter a complete HTTP or HTTPS link", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Enter a complete HTTP or HTTPS link", substring = true).assertExists()
        compose.onNodeWithTag("screen-add_link").assertExists()
        compose.onNodeWithTag("download-url").assertTextContains(unsupportedUrl)
        assertEquals(before, graph.repository.state.value.tasks.map { it.id }.toSet())
        assertEquals("Unsupported input must not be persisted as a task", before,
            runBlocking { graph.db.tasks().tasks().map { it.id }.toSet() })
    }

    /**
     * Supply -e fixtureUrl http://10.0.2.2:8088/bmd-review.bin and -e fixtureSha256 <hex>.
     * Without a supplied reachable fixture this one test is skipped, never replaced by fake success.
     */
    @Test @SdkSuppress(minSdkVersion = 29) fun suppliedDirectLinkDownloadsThroughRustAndPublishesExactBytes() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("fixtureUrl").orEmpty()
        val expectedHash = arguments.getString("fixtureSha256").orEmpty().lowercase()
        assumeTrue("A real reachable direct-link fixture URL is required", url.isNotBlank())
        assumeTrue("A real fixture SHA-256 is required", expectedHash.matches(Regex("[0-9a-f]{64}")))
        compose.waitUntil(15_000) { !graph.repository.state.value.loading }
        val before = graph.repository.state.value.tasks.map { it.id }.toSet()
        share(url)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Start with a link").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download-review").performScrollTo().performClick()
        if (Uri.parse(url).scheme.equals("http", ignoreCase = true)) {
            compose.onNodeWithText("Allow HTTP for this task").performClick()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Make it yours").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Use this network once").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Use this network once").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Use this network once").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Make it yours").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download-filename").performTextReplacement("bmd-device-test-${UUID.randomUUID()}.bin")
        compose.onNodeWithText("Advanced options").performScrollTo().performClick()
        compose.onNodeWithTag("download-sha256").performScrollTo().performTextReplacement(expectedHash)
        compose.onNodeWithTag("download-start").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            graph.repository.state.value.tasks.any { it.id !in before } ||
                compose.onAllNodesWithText("Save another copy").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Save another copy").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Save another copy").performClick()
        compose.waitUntil(15_000) { graph.repository.state.value.tasks.any { it.id !in before } }
        val id = graph.repository.state.value.tasks.first { it.id !in before }.id
        if (compose.onAllNodesWithText("Later", substring = false).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Later", substring = false).performClick()
        compose.waitUntil(120_000) {
            val task = graph.repository.state.value.tasks.find { it.id == id }
            task?.stage in setOf(TransferStage.COMPLETE, TransferStage.FAILED, TransferStage.FILE_CHANGED, TransferStage.NEEDS_LINK, TransferStage.NEEDS_CREDENTIALS) ||
                (task?.stage == TransferStage.WAITING_SAVE && task.errorCode != null && TaskOperation.RETRY_SAVE in task.permittedActions)
        }
        val task = graph.repository.state.value.tasks.first { it.id == id }
        assertEquals("Actual state: ${task.stage}; ${task.detail}; ${task.errorCode}", TransferStage.COMPLETE, task.stage)
        assertTrue("The supplied expected checksum must have been verified", task.sha256Verified)
        val saved = runBlocking { graph.db.tasks().fileForTask(id)!! }
        val actualHash = compose.activity.contentResolver.openInputStream(Uri.parse(saved.uri))!!.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(expectedHash, actualHash)
        assertEquals(saved.bytes, task.totalBytes)
        assertTrue(runBlocking { graph.db.tasks().publication(id)!!.phase } in setOf("PUBLISHED", "CLEANED"))
        // Cleanup only this test-created file and task through the actual user-command boundary.
        removeSavedFixture(saved.id, id)
    }

    /**
     * Required device gate on API 26 and 36. Uses DocumentsUI and its real persistable grant;
     * no test provider, fabricated permission, replacement engine or prewritten result is used.
     * Run on an English-language disposable test device with fixtureUrl / fixtureSha256 extras.
     */
    @Test fun suppliedDirectLinkSavesThroughRealSafPickerAndVerifiesBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("fixtureUrl").orEmpty()
        val expectedHash = arguments.getString("fixtureSha256").orEmpty().lowercase()
        assertTrue("SAF device gate requires a reachable fixtureUrl", url.isNotBlank())
        assertTrue("SAF device gate requires fixtureSha256", expectedHash.matches(Regex("[0-9a-f]{64}")))

        try {
            // Shell creates only the empty fixture folder. The app still needs the picker grant.
            val mkdirOutput = device.executeShellCommand("mkdir -p /sdcard/Download/BmdDeviceTest")
            assertTrue("Could not create the SAF test folder: $mkdirOutput", mkdirOutput.isBlank())
            compose.waitUntil(15_000) { !graph.repository.state.value.loading }
            val before = graph.repository.state.value.tasks.map { it.id }.toSet()
            val initialDestination = graph.repository.state.value.settings.defaultDestination.displayName
            share(url)
            compose.waitUntil(10_000) { hasText("Start with a link") }
            compose.onNodeWithTag("download-review").performScrollTo().performClick()
            if (Uri.parse(url).scheme.equals("http", ignoreCase = true)) {
                compose.onNodeWithText("Allow HTTP for this task").performClick()
            }
            compose.waitUntil(30_000) { hasText("Make it yours") || hasText("Use this network once") }
            if (hasText("Use this network once")) compose.onNodeWithText("Use this network once").performClick()
            compose.waitUntil(30_000) { hasText("Make it yours") }

            compose.onNodeWithText(initialDestination, substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Choose a folder", substring = false).performScrollTo().performClick()
            chooseRealSafTestFolder(device)
            compose.waitUntil(20_000) { hasText("Make it yours") }
            compose.onNodeWithText("Download/BmdDeviceTest", substring = false).assertExists()
            compose.onNodeWithTag("download-filename").performTextReplacement("bmd-saf-test-${UUID.randomUUID()}.bin")
            compose.onNodeWithText("Any network", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("Advanced options").performScrollTo().performClick()
            compose.onNodeWithTag("download-sha256").performScrollTo().performTextReplacement(expectedHash)
            compose.onNodeWithTag("download-start").performScrollTo().performClick()
            compose.waitUntil(15_000) { graph.repository.state.value.tasks.any { it.id !in before } || hasText("Save another copy") }
            if (hasText("Save another copy")) compose.onNodeWithText("Save another copy").performClick()
            compose.waitUntil(15_000) { graph.repository.state.value.tasks.any { it.id !in before } }
            val id = graph.repository.state.value.tasks.first { it.id !in before }.id
            if (hasText("Later")) compose.onNodeWithText("Later", substring = false).performClick()

            // Network completion hands SAF work back to a visible, explicit local-save action.
            compose.waitUntil(120_000) {
                graph.repository.state.value.tasks.find { it.id == id }?.stage in setOf(
                    TransferStage.WAITING_SAVE, TransferStage.COMPLETE, TransferStage.FAILED,
                    TransferStage.FILE_CHANGED, TransferStage.NEEDS_LINK, TransferStage.NEEDS_CREDENTIALS,
                )
            }
            val downloaded = graph.repository.state.value.tasks.first { it.id == id }
            assertEquals("Expected visible SAF save admission; actual ${downloaded.stage}: ${downloaded.detail}",
                TransferStage.WAITING_SAVE, downloaded.stage)
            assertEquals(DestinationKind.DOCUMENT_TREE, downloaded.destination.kind)
            val treeUri = Uri.parse(requireNotNull(downloaded.destination.treeUri))
            assertEquals("primary:Download/BmdDeviceTest", DocumentsContract.getTreeDocumentId(treeUri))
            assertTrue("The actual picker grant must be persisted with read and write access",
                compose.activity.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission && it.isWritePermission
                })
            compose.onNodeWithText("Retry save", substring = false).performScrollTo().performClick()
            compose.waitUntil(120_000) {
                // Retry admission and saving are asynchronous. Await the real terminal result;
                // only a settled error hold can end this wait before completion.
                val task = graph.repository.state.value.tasks.find { it.id == id }
                task?.stage in setOf(
                    TransferStage.COMPLETE, TransferStage.FAILED, TransferStage.FILE_CHANGED,
                    TransferStage.NEEDS_LINK, TransferStage.NEEDS_CREDENTIALS,
                ) || (task?.stage == TransferStage.WAITING_SAVE && task.errorCode != null &&
                    TaskOperation.RETRY_SAVE in task.permittedActions)
            }
            val task = graph.repository.state.value.tasks.first { it.id == id }
            assertEquals("Actual SAF state: ${task.stage}; ${task.detail}; ${task.errorCode}", TransferStage.COMPLETE, task.stage)
            assertTrue("Rust must verify the supplied expected checksum", task.sha256Verified)
            val saved = runBlocking { graph.db.tasks().fileForTask(id)!! }
            val publication = runBlocking { graph.db.tasks().publication(id)!! }
            assertEquals("SAF", publication.adapter)
            assertEquals("The final file must keep the journaled document URI", publication.uri, saved.uri)
            val savedUri = Uri.parse(saved.uri)
            assertTrue(DocumentsContract.isDocumentUri(compose.activity, savedUri))
            assertEquals(expectedHash, sha256(savedUri))
            assertEquals(saved.bytes, task.totalBytes)
            assertTrue(publication.phase in setOf("PUBLISHED", "CLEANED"))

            // Delete only the document and history that this successful test created.
            removeSavedFixture(saved.id, id)
        } catch (failure: Throwable) {
            runCatching { recordDeviceFailure(device, "saf-picker", failure) }
            throw failure
        }
    }

    private fun chooseRealSafTestFolder(device: UiDevice) {
        val documentsUi = Pattern.compile(".*documentsui.*")
        assertTrue("Android DocumentsUI did not open",
            device.wait(Until.hasObject(By.pkg(documentsUi)), 15_000))
        device.waitForIdle(1_500)
        // API 26 can hide the external-storage root until the user chooses this menu option.
        device.findObject(By.desc("More options"))?.let { overflow ->
            overflow.click()
            val showStorage = device.wait(Until.findObject(By.text(Pattern.compile("(?i)Show (internal storage|SD card)"))), 1_500)
            if (showStorage != null) showStorage.click() else device.pressBack()
        }
        // The observed API 36 picker opens directly at the internal-storage root and omits
        // its drawer button. Use the visible root children there; other versions need the drawer.
        // The returned grant is still checked against primary:Download/BmdDeviceTest below.
        if (device.findObject(By.pkg(documentsUi).text("Download")) == null) {
            val showRoots = device.findObject(By.pkg(documentsUi).desc("Show roots"))
                ?: device.findObject(By.pkg(documentsUi).res(Pattern.compile(".*:id/toolbar")))
                    ?.findObject(By.clazz("android.widget.ImageButton").clickable(true))
                ?: error("The system folder picker has neither the Download directory nor a storage navigation control")
            showRoots.click()
            // API 26 diagnostic XML contains this drawer and title, but root loading can outlive UI idle.
            assertTrue("The DocumentsUI storage roots did not become available",
                device.wait(Until.hasObject(By.pkg(documentsUi).res(Pattern.compile(".*:id/roots_list")).enabled(true)), 10_000))
            val storageLabels = Pattern.compile("(?i)(?:" + Pattern.quote(Build.MODEL) +
                "|Android SDK built for x86_64|Internal (?:shared )?storage)")
            val internalStorage = device.wait(Until.findObject(
                By.pkg(documentsUi).res("android:id/title").text(storageLabels).enabled(true)), 15_000)
                ?: error("The real internal-storage root was not available in DocumentsUI")
            internalStorage.click()
        }
        findDirectoryEntry(device, "Download").click()
        findDirectoryEntry(device, "BmdDeviceTest").click()
        val select = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/action_menu_select")).enabled(true)), 5_000)
            ?: device.wait(Until.findObject(By.text(Pattern.compile("(?i)(use this folder|select this folder|select)")).enabled(true)), 5_000)
            ?: error("The Android picker did not allow choosing the test subfolder")
        select.click()
        // Newer Android versions require a second native confirmation of the tree grant.
        val allow = device.wait(Until.findObject(By.res("android:id/button1")), 2_000)
        if (allow != null) {
            assertTrue("Unexpected native confirmation: ${allow.text}",
                allow.text.orEmpty().matches(Regex("(?i)(allow|use this folder|select)")))
            allow.click()
        }
        assertTrue("The picker never returned its real grant to Black Marlin",
            device.wait(Until.hasObject(By.pkg(InstrumentationRegistry.getInstrumentation().targetContext.packageName)), 20_000))
    }

    private fun findDirectoryEntry(device: UiDevice, name: String): UiObject2 {
        device.wait(Until.findObject(By.text(name)), 3_000)?.let { return it }
        repeat(10) {
            val list = device.findObject(By.scrollable(true)) ?: error("No directory list when looking for $name")
            val hasMore = list.scroll(Direction.DOWN, 0.75f)
            device.findObject(By.text(name))?.let { return it }
            if (!hasMore) error("The real system picker did not show the test directory $name")
        }
        error("The real system picker did not show the test directory $name")
    }

    private fun hasText(text: String) = compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()

    private fun removeSavedFixture(fileId: String, taskId: String) {
        // Published/Complete becomes visible before the executor finishes private cleanup.
        // Wait for its actual durable ownership release before requesting history deletion.
        compose.waitUntil(15_000) {
            runBlocking { requireNotNull(graph.db.tasks().task(taskId)).ownerSession == null }
        }
        runBlocking {
            assertEquals("The actual save must finish private cleanup before fixture removal", "CLEANED",
                requireNotNull(graph.db.tasks().publication(taskId)).phase)
            assertFalse("The saved transfer's private staging directory must be removed", graph.directory(taskId).exists())
            assertTrue("Automatic completion cleanup must remove the task's source URL",
                requireNotNull(graph.db.tasks().task(taskId)).sourceUrl.isEmpty())
            val deleted = graph.repository.execute(BmdCommand.RemoveFiles(setOf(fileId), deleteFromDevice = true))
            assertTrue(deleted.failedItems.toString(), deleted.failedItems.isEmpty())
            val removed = graph.repository.execute(BmdCommand.RemoveTransfers(setOf(taskId)))
            assertTrue(removed.failedItems.toString(), removed.failedItems.isEmpty())
            assertNull("Fixture task history must be removed", graph.db.tasks().task(taskId))
            assertNull("Fixture file index must be removed", graph.db.tasks().fileForTask(taskId))
        }
    }

    private fun sha256(uri: Uri): String = compose.activity.contentResolver.openInputStream(uri)!!.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recordDeviceFailure(device: UiDevice, label: String, failure: Throwable) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "device-test-evidence").apply { mkdirs() }
        val prefix = "${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}-api${Build.VERSION.SDK_INT}"
        // Preserve the failure first. Provider paths/URIs, source URLs and credentials are excluded.
        File(directory, "$prefix.txt").writeText("${failure.javaClass.name}: ${failure.message}\n" +
            graph.repository.state.value.tasks.joinToString("\n") { "${it.id} ${it.stage} ${it.errorCode}: ${it.detail}" })
        runCatching {
            val phases = runBlocking { withTimeout(3_000) {
                val rows = mutableListOf<String>()
                for (task in graph.db.tasks().tasks()) {
                    val publication = graph.db.tasks().publication(task.id)
                    rows += "${task.id} total=${task.totalBytes} verified=${task.verified} " +
                        "phase=${publication?.phase} adapter=${publication?.adapter} copied=${publication?.copiedBytes} " +
                        "expected=${publication?.expectedBytes} commit=${publication?.commitPlanned}"
                }
                rows.joinToString("\n")
            } }
            File(directory, "$prefix.txt").appendText("\n$phases\n")
        }
        runCatching { device.dumpWindowHierarchy(File(directory, "$prefix.xml")) }
        runCatching { device.takeScreenshot(File(directory, "$prefix.png")) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nPrimary device failure evidence: ${directory.absolutePath}/$prefix.*\n")
        })
    }

    private fun share(text: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
