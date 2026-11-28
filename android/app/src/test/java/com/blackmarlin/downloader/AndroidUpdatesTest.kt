package com.blackmarlin.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidUpdatesTest {
    private val version = "1.10.0"
    private val apk = "BMD-$version-android.apk"
    private val sum = "BMD-$version-android-SHA256SUMS.txt"
    private fun release(checksum: String = sum, prerelease: Boolean = false) = """
        {"tag_name":"v$version","prerelease":$prerelease,
         "assets":[{"name":"$apk"},{"name":"$checksum"}]}
    """.trimIndent()

    @Test fun offersOnlyNewerStableReleasesWithBothAndroidAssets() {
        assertEquals(AndroidRelease(version), androidRelease(release(), "1.9.0"))
        assertNull(androidRelease(release(), version))
        assertNull(androidRelease(release(), "2.0.0"))
        assertNull(androidRelease(release(prerelease = true), "1.9.0"))
        assertNull(androidRelease(release(checksum = "SHA256SUMS.txt"), "1.9.0"))
    }

    @Test fun requiresOneValidChecksumForTheExactApk() {
        val digest = "AB".repeat(32)
        val entry = "$digest  *$apk"
        assertEquals(digest.lowercase(), androidChecksum(entry, apk))
        assertNull(androidChecksum("$entry\n$entry", apk))
        assertNull(androidChecksum("bad  $apk", apk))
        assertNull(androidChecksum("$digest  another.apk", apk))
    }
}
