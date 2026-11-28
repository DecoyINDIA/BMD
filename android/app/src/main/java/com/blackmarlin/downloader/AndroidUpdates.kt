package com.blackmarlin.downloader

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val REPOSITORY = "DecoyINDIA/BMD"
private const val RELEASE_FEED = "https://api.github.com/repos/$REPOSITORY/releases/latest"
private const val MAX_APK_BYTES = 128L * 1024 * 1024
internal const val UPDATE_CHECK_INTERVAL = 60 * 60 * 1000L

internal data class AndroidRelease(val version: String) {
    val apkName = "BMD-$version-android.apk"
    val checksumName = "BMD-$version-android-SHA256SUMS.txt"
    fun asset(name: String) = "https://github.com/$REPOSITORY/releases/download/v$version/$name"
}

internal sealed interface AndroidUpdate {
    data class Available(val release: AndroidRelease, val retry: Boolean = false) : AndroidUpdate
    data class Downloading(val version: String) : AndroidUpdate
    data class Ready(val version: String, val apk: File, val openInstaller: Boolean = true) : AndroidUpdate
}

internal class AndroidUpdates(private val app: Application) : AndroidViewModel(app) {
    private val mutableUpdate = MutableStateFlow<AndroidUpdate?>(null)
    val update = mutableUpdate.asStateFlow()
    private var lastCheck: Long? = null
    private var lastOffer: String? = null

    @Suppress("DEPRECATION")
    fun check() {
        if (!BuildConfig.GITHUB_UPDATES || BuildConfig.DEBUG || mutableUpdate.value != null) return
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            app.packageManager.getInstallerPackageName(app.packageName)
        }
        if (installer == "com.android.vending") return
        val now = SystemClock.elapsedRealtime()
        if (lastCheck?.let { now - it < UPDATE_CHECK_INTERVAL } == true) return
        lastCheck = now
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val body = request(RELEASE_FEED) { it.readNBytesCompat(1024 * 1024) }
                    androidRelease(body.decodeToString(), BuildConfig.VERSION_NAME)
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                return@launch
            }
            if (result != null && result.version != lastOffer) {
                lastOffer = result.version
                mutableUpdate.value = AndroidUpdate.Available(result)
            }
        }
    }

    fun dismiss() {
        if (mutableUpdate.value !is AndroidUpdate.Downloading) mutableUpdate.value = null
    }

    fun download() {
        val release = (mutableUpdate.value as? AndroidUpdate.Available)?.release ?: return
        mutableUpdate.value = AndroidUpdate.Downloading(release.version)
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { fetchVerifiedApk(release) }
                mutableUpdate.value = AndroidUpdate.Ready(release.version, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableUpdate.value = AndroidUpdate.Available(release, retry = true)
            }
        }
    }

    fun installerOpened() {
        val ready = mutableUpdate.value as? AndroidUpdate.Ready ?: return
        mutableUpdate.value = ready.copy(openInstaller = false)
    }

    private suspend fun fetchVerifiedApk(release: AndroidRelease): File {
        val checksum = request(release.asset(release.checksumName)) {
            androidChecksum(it.readNBytesCompat(64 * 1024).decodeToString(), release.apkName)
        } ?: throw IOException("Missing APK checksum")
        val directory = File(app.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "bmd-update.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val coroutine = currentCoroutineContext()
            request(release.asset(release.apkName)) { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_APK_BYTES) throw IOException("Update APK exceeds size limit")
                        digest.update(buffer, 0, size)
                        output.write(buffer, 0, size)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (checksum != actual) throw IOException("APK checksum mismatch")
            verifyPackage(apk, release.version)
            return apk
        } catch (e: Exception) {
            apk.delete()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, version: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val manager = app.packageManager
        val installed = manager.getPackageInfo(app.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("Invalid update APK")
        fun signers(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else info.signatures?.toSet().orEmpty()
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val installedSigners = signers(installed)
        if (candidate.packageName != app.packageName || candidate.versionName != version ||
            code(candidate) <= code(installed) || installedSigners.isEmpty() || signers(candidate) != installedSigners
        ) throw IOException("APK does not match this BMD installation")
    }
}

internal fun androidRelease(body: String, installed: String): AndroidRelease? {
    val json = JSONObject(body)
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
    val tag = json.optString("tag_name")
    val version = tag.removePrefix("v")
    val pattern = Regex("""[0-9]{1,9}(\.[0-9]{1,9}){2}""")
    if (tag == version || !pattern.matches(version) || !pattern.matches(installed)) return null
    val next = version.split('.').map(String::toInt)
    val current = installed.split('.').map(String::toInt)
    if ((next.indices.firstOrNull { next[it] != current[it] }?.let { next[it] > current[it] }) != true) return null
    val release = AndroidRelease(version)
    val assets = json.optJSONArray("assets") ?: return null
    val names = (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }.toSet()
    return release.takeIf { it.apkName in names && it.checksumName in names }
}

internal fun androidChecksum(body: String, filename: String): String? {
    val line = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")
    return body.lineSequence().mapNotNull { line.matchEntire(it.trim()) }
        .filter { it.groupValues[2] == filename }
        .singleOrNull()?.groupValues?.get(1)?.lowercase()
}

private fun <T> request(url: String, read: (java.io.InputStream) -> T): T {
    val connection = URI(url).toURL().openConnection() as HttpsURLConnection
    return try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "BMD-Android/${BuildConfig.VERSION_NAME}")
        if (connection.responseCode != 200) throw IOException("Update server returned ${connection.responseCode}")
        connection.inputStream.use(read)
    } finally {
        connection.disconnect()
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("Update metadata exceeds size limit")
        output.write(buffer, 0, count)
    }
}
