package com.blackmarlin.downloader.releasecheck

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box acceptance for the exact optimized production APK. There are no app-class imports,
 * test-only production endpoints, reflection, native replacements or certificate overrides.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var deadline = 0L
    private var phase = "initialization"
    private var fixtureName = ""
    private val checkpointEvidence = JSONObject()

    @Test(timeout = 300_000)
    fun pinnedHttpsReviewAndHttpFixturePublishThroughMinifiedApp() {
        deadline = SystemClock.elapsedRealtime() + 285_000
        val originalIdleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().setWaitForIdleTimeout(500)
        try {
            val arguments = InstrumentationRegistry.getArguments()
            val fixtureUrl = requireNotNull(arguments.getString("fixtureUrl")) { "fixtureUrl is required" }
            val fixtureSha = requireHash(arguments.getString("fixtureSha256"), "fixtureSha256")
            val releaseApkSha = requireHash(arguments.getString("releaseApkSha256"), "releaseApkSha256")
            val fixtureBytes = arguments.getString("fixtureBytes")?.toLong() ?: 65_536L
            require(fixtureBytes > 0)
            val fixtureUri = Uri.parse(fixtureUrl)
            require(fixtureUri.scheme == "http" && fixtureUri.host == "10.0.2.2" &&
                fixtureUri.userInfo == null && fixtureUri.query == null && fixtureUri.fragment == null) {
                "Use the credential-free emulator-host HTTP fixture"
            }
            assertEquals(TARGET_PACKAGE, context.packageName)
            assertEquals("The harness must run under the actual production app UID", context.applicationInfo.uid, Process.myUid())
            assertEquals("The optimized app must remain non-debuggable", 0,
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
            assertEquals("This gate uses the English UI", "en", context.resources.configuration.locales[0].language)
            assertEquals("The installed APK must be the CI-verified, ephemeral-signed release payload",
                releaseApkSha, File(context.applicationInfo.sourceDir).inputStream().use { hash(it).first })

            phase = "allow-network-through-settings"
            val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE))
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await(By.pkg(TARGET_PACKAGE), 20_000)
            clickText("Settings")
            clickText("Network and performance", mergedRow = true)
            clickText("Any network", mergedRow = true)
            clickText("Save defaults")
            await(By.textContains("Settings saved"), 10_000)

            phase = "pinned-https-review"
            share(PINNED_HTTPS)
            reviewSharedLink(plainHttp = false)
            // The pinned source is 35,149 bytes. This checks its real, rounded Review metadata;
            // it is deliberately separate from the full-body fixture proof below.
            await(By.text("34.3 KiB"), 10_000)
            await(By.text("https://raw.githubusercontent.com"), 10_000)
            assertFalse("Offline fallback must not satisfy the HTTPS gate", device.hasObject(By.textContains("Offline.")))
            assertFalse("An HTML response must not satisfy the pinned-source gate", device.hasObject(By.text("This may be a webpage")))
            assertFalse("The public pinned source must not require authentication", device.hasObject(By.text("Source authentication required")))
            captureCheckpoint("https-review")

            phase = "http-fixture-review"
            share(fixtureUrl)
            reviewSharedLink(plainHttp = true)
            fixtureName = "bmd-release-test-${UUID.randomUUID()}.bin"
            editField("File name", fixtureName)
            openAdvancedOptions()
            editField("Expected SHA-256 (optional)", fixtureSha)
            clickText("Start download")

            phase = "native-transfer-and-publication"
            // First-run notification education is optional, but it must not cover the task UI.
            val row = waitForPublishedFixture(fixtureName, fixtureBytes, 100_000)
            dismissNotificationEducation()
            scrollTo(By.text("Complete"), 20_000)
            scrollTo(By.text("Matches your expected SHA-256"), 10_000)
            assertEquals(TARGET_PACKAGE, row.owner)
            assertEquals(0, row.pending)
            assertEquals(fixtureBytes, row.bytes)
            phase = "content-resolver-byte-proof"
            val bodyProof = requireNotNull(context.contentResolver.openInputStream(row.uri)) {
                "Published MediaStore content could not be opened"
            }.use(::hash)
            assertEquals("The actual published file bytes must match the supplied fixture SHA-256", fixtureSha, bodyProof.first)
            assertEquals("The actual content stream must have the expected length", fixtureBytes, bodyProof.second)

            phase = "files-index-and-explicit-cleanup"
            await(By.desc("Back"), 5_000).click()
            clickText("Files")
            await(By.text("Saved and ready"), 10_000)
            clickText(fixtureName, mergedRow = true)
            await(By.text("Matches the expected SHA-256"), 10_000)
            captureCheckpoint("published-fixture")
            clickText("Delete file")
            await(By.text("Delete 1 file?"), 5_000)
            clickText("Delete files")
            waitForCondition(15_000, "The exact fixture document was not deleted") {
                queryFixture(fixtureName) == null
            }
            // A successful Delete returns File detail to the Files list.
            await(By.text("Transfers"), 10_000).click()
            clickText(fixtureName, mergedRow = true)
            clickText("Remove from transfer history")
            await(By.text("Remove transfer history?"), 5_000)
            clickText("Remove history")
            await(By.textContains("Transfer history updated"), 10_000)
            assertNull("Cleanup must not leave the test's provider document", queryFixture(fixtureName))

            phase = "complete"
            writeEvidence("result.json", JSONObject()
                .put("targetPackage", TARGET_PACKAGE)
                .put("installedApkSha256", releaseApkSha)
                .put("appDebuggable", false)
                .put("httpsReview", "pinned origin and 34.3 KiB metadata; not a full HTTPS body hash")
                .put("mediaStoreOwner", row.owner)
                .put("mediaStorePending", row.pending)
                .put("fixtureBytes", bodyProof.second)
                .put("fixtureSha256", bodyProof.first)
                .put("fixtureDeleted", true)
                .put("uiEvidence", checkpointEvidence)
                .put("method", "pinnedHttpsReviewAndHttpFixturePublishThroughMinifiedApp")
                .toString(2))
        } catch (failure: Throwable) {
            recordPrimaryFailure(failure)
            throw failure
        } finally {
            Configurator.getInstance().setWaitForIdleTimeout(originalIdleTimeout)
        }
    }

    private fun share(url: String) {
        // Resolve the production exported share Activity by its public intent filter, without
        // referring to an app class that R8 could rename or retaining that class for this test.
        val request = Intent(Intent.ACTION_SEND).setPackage(TARGET_PACKAGE).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertNotNull("Production package has no text-share entry point", context.packageManager.resolveActivity(request, 0))
        context.startActivity(request)
        await(By.text("Start with a link"), 15_000)
    }

    private fun reviewSharedLink(plainHttp: Boolean) {
        clickText("Review download")
        if (plainHttp) {
            await(By.text("Allow an unencrypted connection?"), 10_000)
            clickText("Allow HTTP for this task")
        }
        val end = SystemClock.elapsedRealtime() + remaining(50_000)
        while (SystemClock.elapsedRealtime() < end) {
            if (device.hasObject(By.text("Use this network once"))) clickText("Use this network once")
            if (device.hasObject(By.text("Make it yours"))) return
            SystemClock.sleep(150)
        }
        throw AssertionError("The actual source probe did not reach Review during $phase")
    }

    private fun editField(label: String, value: String) {
        val end = SystemClock.elapsedRealtime() + remaining(15_000)
        // Keep five seconds of the same field deadline for input and exact confirmation.
        val scrollBudget = maxOf(1L, end - SystemClock.elapsedRealtime() - 5_000)
        scrollTo(By.text(label), scrollBudget)
        // The observed Compose field contains its label as a direct accessibility child.
        // Never edit an unrelated field merely because it is closest to the label's bounds.
        val selector = By.pkg(TARGET_PACKAGE).clazz("android.widget.EditText").enabled(true)
            .hasChild(By.pkg(TARGET_PACKAGE).text(label))
        var writeAttempts = 0
        var nextWriteAt = 0L
        var focusAttempted = false
        var matchingReads = 0
        while (SystemClock.elapsedRealtime() < end) {
            try {
                val field = device.findObjects(selector).filter {
                    val bounds = it.visibleBounds
                    bounds.height() > 0 && bounds.width() > 0
                }.singleOrNull()
                if (field != null && field.text == value) {
                    // ACTION_SET_TEXT can return before Compose publishes its updated value.
                    // Confirm exact equality through fresh nodes on successive observations.
                    matchingReads++
                    if (matchingReads >= 2) return
                } else {
                    matchingReads = 0
                    if (field != null && writeAttempts < 3 &&
                        SystemClock.elapsedRealtime() >= nextWriteAt) {
                        if (writeAttempts > 0 && !focusAttempted) {
                            focusAttempted = true
                            if (!field.isFocused) field.click()
                            nextWriteAt = SystemClock.elapsedRealtime() + 300
                        } else {
                            writeAttempts++
                            nextWriteAt = SystemClock.elapsedRealtime() + 1_000
                            field.text = value
                        }
                    }
                }
            } catch (_: StaleObjectException) {
                // Reacquire only detached nodes inside the same field/overall deadline.
                matchingReads = 0
            }
            val settle = minOf(200L, end - SystemClock.elapsedRealtime())
            if (settle > 0) SystemClock.sleep(settle)
        }
        throw AssertionError("The field $label did not accept the exact confirmed value during $phase")
    }

    private fun openAdvancedOptions() {
        val end = SystemClock.elapsedRealtime() + remaining(15_000)
        scrollTo(By.text("Advanced options"), maxOf(1L, end - SystemClock.elapsedRealtime() - 5_000))
        val labelSelector = By.pkg(TARGET_PACKAGE).text("Advanced options").enabled(true)
        var previousBounds: Pair<Rect, Rect>? = null
        var stableSince = 0L
        var clicked = false
        while (SystemClock.elapsedRealtime() < end) {
            var ready: UiObject2? = null
            try {
                val label = device.findObjects(labelSelector).filter {
                    val bounds = it.visibleBounds
                    bounds.height() > 0 && bounds.width() > 0
                }.singleOrNull()
                // The observed disclosure label is the direct child of its real button.
                val button = label?.parent
                if (label != null && button != null &&
                    button.applicationPackage == TARGET_PACKAGE && button.isEnabled && button.isClickable) {
                    val labelBounds = label.visibleBounds
                    val buttonBounds = button.visibleBounds
                    if (labelBounds.height() > 0 && labelBounds.width() > 0 && buttonBounds.contains(labelBounds)) {
                        val bounds = labelBounds to buttonBounds
                        if (bounds == previousBounds) {
                            // Both nodes are reacquired on each observation. The old helper
                            // tapped the text child while this post-swipe button still moved.
                            if (SystemClock.elapsedRealtime() - stableSince >= 500) ready = button
                        } else {
                            previousBounds = bounds
                            stableSince = SystemClock.elapsedRealtime()
                        }
                    } else previousBounds = null
                } else previousBounds = null
            } catch (_: StaleObjectException) {
                previousBounds = null
            }
            if (ready != null) {
                remaining(1)
                // Make one physical activation outside stale-node retry handling. A toggle
                // must not be repeated merely because its new content starts offscreen.
                ready.click()
                clicked = true
                break
            }
            val settle = minOf(200L, end - SystemClock.elapsedRealtime())
            if (settle > 0) SystemClock.sleep(settle)
        }
        assertTrue("The Advanced options button did not settle before activation during $phase", clicked)
        // Require actual expansion before sending checksum input, including a bounded scroll
        // to the enabled editor when the newly revealed section exceeds the viewport.
        scrollTo(By.clazz("android.widget.EditText").enabled(true)
            .hasChild(By.pkg(TARGET_PACKAGE).text("Expected SHA-256 (optional)")), 10_000)
    }

    private fun clickText(text: String, mergedRow: Boolean = false) {
        // Clickable Compose rows can expose their title and subtitle as one accessibility text.
        scrollTo(if (mergedRow) By.textContains(text) else By.text(text), 15_000).click()
    }

    private fun scrollTo(selector: BySelector, timeout: Long): UiObject2 {
        val end = SystemClock.elapsedRealtime() + remaining(timeout)
        val target = selector.pkg(TARGET_PACKAGE)
        fun visibleTarget(): UiObject2? = device.findObjects(target).firstOrNull {
            val bounds = it.visibleBounds
            bounds.height() > 0 && bounds.width() > 0
        }
        var gestures = 0
        while (SystemClock.elapsedRealtime() < end) {
            try {
                visibleTarget()?.let { return it }
                // Reserve the tail for fresh observations instead of starting another stroke.
                if (gestures < 12 && end - SystemClock.elapsedRealtime() > 2_000) {
                    // Reacquire the actual app viewport, including keyboard-driven resizing.
                    val bounds = device.findObjects(By.pkg(TARGET_PACKAGE).scrollable(true))
                        .map { it.visibleBounds }
                        .filter { it.height() >= 8 && it.width() > 0 }
                        .maxByOrNull { it.height() }
                    if (bounds != null) {
                        val upper = bounds.top + bounds.height() / 4
                        val lower = bounds.bottom - bounds.height() / 4
                        val down = gestures < 6
                        // Eighty injected steps took 3–6 seconds on the observed emulator.
                        // A shorter continuous, overlapping stroke leaves time to observe it.
                        assertTrue("The app viewport swipe could not be injected during $phase",
                            device.swipe(bounds.centerX(), if (down) lower else upper,
                                bounds.centerX(), if (down) upper else lower, 24))
                        gestures++
                        remaining(1)
                        visibleTarget()?.let { return it }
                    }
                }
            } catch (_: StaleObjectException) {
                // Only detached accessibility nodes are retried inside the bounded search.
            }
            val settle = minOf(200L, end - SystemClock.elapsedRealtime())
            if (settle > 0) SystemClock.sleep(settle)
        }
        // A completed input operation may consume the last local slice. Observe its result
        // once more under the unchanged global deadline before declaring the target absent.
        remaining(1)
        try { visibleTarget()?.let { return it } } catch (_: StaleObjectException) { }
        throw AssertionError("UI element $selector was unavailable during $phase")
    }

    private fun await(selector: BySelector, timeout: Long): UiObject2 =
        device.wait(Until.findObject(selector), remaining(timeout))
            ?: throw AssertionError("UI element $selector was unavailable during $phase")

    private fun dismissNotificationEducation() {
        if (device.hasObject(By.text("See progress outside the app")))
            device.findObject(By.text("Later"))?.click()
    }

    private data class PublishedRow(val uri: Uri, val bytes: Long, val pending: Int, val owner: String)

    private fun waitForPublishedFixture(name: String, expectedBytes: Long, timeout: Long): PublishedRow {
        var published: PublishedRow? = null
        waitForCondition(timeout, "The fixture never reached a nonpending MediaStore publication with the expected indexed length") {
            dismissNotificationEducation()
            val row = queryFixture(name)
            // The provider can clear IS_PENDING before its metadata scan updates _size.
            // Wait for both states within the same deadline; the final row, actual stream
            // length and full SHA-256 assertions remain mandatory after this returns.
            if (row != null && row.pending == 0 && row.bytes == expectedBytes) {
                published = row
                true
            } else false
        }
        return requireNotNull(published)
    }

    private fun queryFixture(name: String): PublishedRow? {
        require(name.startsWith("bmd-release-test-") && name.endsWith(".bin"))
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val arguments = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(name, "Download/BlackMarlin/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return context.contentResolver.query(collection, projection, arguments, null)?.use { cursor ->
            assertTrue("UUID fixture name unexpectedly matches more than one document", cursor.count <= 1)
            if (!cursor.moveToFirst()) null else PublishedRow(
                ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getLong(1),
                cursor.getInt(2), cursor.getString(3).also { assertEquals(TARGET_PACKAGE, it) },
            )
        }
    }

    private fun waitForCondition(timeout: Long, failure: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + remaining(timeout)
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(200)
        }
        throw AssertionError("$failure during $phase")
    }

    private fun remaining(maximum: Long): Long {
        val value = deadline - SystemClock.elapsedRealtime()
        if (value <= 0) throw AssertionError("Release acceptance deadline exceeded during $phase")
        return minOf(value, maximum)
    }

    private fun hash(input: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            remaining(1)
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            count += read
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) } to count
    }

    private fun requireHash(value: String?, label: String): String = requireNotNull(value) { "$label is required" }
        .lowercase(Locale.ROOT).also { require(it.matches(Regex("[0-9a-f]{64}"))) { "$label must be SHA-256 hex" } }

    private fun evidenceDirectory() = File(context.getExternalFilesDir(null) ?: context.filesDir, "release-test-evidence")
        .apply { mkdirs() }

    private fun writeEvidence(name: String, text: String) { File(evidenceDirectory(), name).writeText(text) }

    private fun captureCheckpoint(name: String) {
        // Diagnostic evidence is separate from functional assertions and shares the test deadline.
        // Do not start optional captures when less than six seconds remain for the real workflow.
        val status = JSONObject().put("phase", phase)
        if (deadline - SystemClock.elapsedRealtime() < 6_000) {
            status.put("xmlWritten", false).put("pngWritten", false).put("reason", "Insufficient diagnostic time budget")
        } else {
            val xml = runCatching {
                remaining(1)
                device.dumpWindowHierarchy(File(evidenceDirectory(), "$name.xml"))
                true
            }
            val png = runCatching {
                remaining(1)
                device.takeScreenshot(File(evidenceDirectory(), "$name.png"))
            }
            status.put("xmlWritten", xml.getOrDefault(false)).put("pngWritten", png.getOrDefault(false))
            xml.exceptionOrNull()?.let { status.put("xmlError", it.javaClass.simpleName) }
            png.exceptionOrNull()?.let { status.put("pngError", it.javaClass.simpleName) }
        }
        checkpointEvidence.put(name, status)
        runCatching { writeEvidence("$name.capture.json", status.toString(2)) }
            .onFailure { Log.w("BmdReleaseAcceptance", "Could not persist $name diagnostic status", it) }
    }

    private fun recordPrimaryFailure(failure: Throwable) {
        // Save the original body failure first. There is no ActivityScenario teardown to replace it.
        val description = "Phase: $phase\nFixture: $fixtureName\n${failure.stackTraceToString()}"
        Log.e("BmdReleaseAcceptance", "Primary failure during $phase", failure)
        runCatching { writeEvidence("primary-failure.txt", description) }
        runCatching { device.dumpWindowHierarchy(File(evidenceDirectory(), "failure.xml")) }
        runCatching { device.takeScreenshot(File(evidenceDirectory(), "failure.png")) }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.blackmarlin.downloader"
        private const val PINNED_HTTPS = "https://raw.githubusercontent.com/DecoyINDIA/BMD/6b4038a7691bea096d7f035475ebc277db23c11a/LICENSE"
    }
}
