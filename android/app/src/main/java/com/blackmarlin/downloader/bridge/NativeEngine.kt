package com.blackmarlin.downloader.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object NativeEngine {
    init { System.loadLibrary("bmd_android_bridge") }
    @JvmStatic external fun nativeInitialize(applicationContext: Context): String
    @JvmStatic external fun nativeCommand(requestJson: String): String
    @JvmStatic external fun nativeSnapshot(handle: Long): String
}

class NativeFailure(val code: String, val retryable: Boolean = false) : Exception(code)

data class NativeSnapshot(
    val phase: String,
    val receivedBytes: Long,
    val checkpointBytes: Long,
    val totalBytes: Long?,
    val resumable: Boolean,
    val fileName: String?,
    val origin: String?,
    val webPage: Boolean,
    val errorCode: String?,
    val retryable: Boolean,
    val terminal: Boolean,
    val writerReleased: Boolean,
)

/** Native calls exchange bounded summaries. Payload bytes never cross JNI. */
class EngineBridge(private val context: Context) {
    private val lock = Mutex()
    @Volatile private var initialized = false
    suspend fun initialize() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!initialized) {
                try { unpack(NativeEngine.nativeInitialize(context.applicationContext)); initialized = true }
                catch (_: LinkageError) { throw NativeFailure("ENGINE_UNAVAILABLE") }
            }
        }
    }
    private fun unpack(raw: String): JSONObject {
        if (raw.toByteArray().size > 65536) throw NativeFailure("BRIDGE_PROTOCOL")
        val json = try { JSONObject(raw) } catch (_: Exception) { throw NativeFailure("BRIDGE_PROTOCOL") }
        if (json.optInt("apiVersion") != 1) throw NativeFailure("BRIDGE_PROTOCOL")
        if (!json.optBoolean("ok")) {
            val error = json.optJSONObject("error")
            throw NativeFailure(error?.optString("code")?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "NATIVE_FAILURE", error?.optBoolean("retryable") ?: false)
        }
        return json.optJSONObject("data") ?: JSONObject()
    }
    suspend fun command(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        initialize()
        val raw = request.toString()
        if (raw.toByteArray().size > 32768) throw NativeFailure("REQUEST_TOO_LARGE")
        try { unpack(NativeEngine.nativeCommand(raw)) } catch (_: LinkageError) { throw NativeFailure("ENGINE_UNAVAILABLE") }
    }
    suspend fun snapshot(handle: Long): NativeSnapshot = withContext(Dispatchers.IO) {
        val j = unpack(NativeEngine.nativeSnapshot(handle))
        NativeSnapshot(
            j.getString("phase"), j.optLong("receivedBytes").coerceAtLeast(0), j.optLong("checkpointBytes").coerceAtLeast(0),
            if (j.isNull("totalBytes")) null else j.optLong("totalBytes").takeIf { it >= 0 },
            j.optBoolean("resumable"), j.optString("fileName").takeUnless { it.isEmpty() || it == "null" },
            j.optString("sourceOrigin").takeUnless { it.isEmpty() || it == "null" }, j.optBoolean("webPage"),
            j.optString("errorCode").takeUnless { it.isEmpty() || it == "null" }, j.optBoolean("retryable"),
            j.optBoolean("terminal"), j.optBoolean("writerReleased"),
        )
    }
    suspend fun stop(handle: Long) { command(JSONObject().put("command", "stop").put("handle", handle)) }
    suspend fun release(handle: Long) { command(JSONObject().put("command", "release").put("handle", handle)) }
    suspend fun invalidateNetwork(handle: Long, generation: Long) {
        command(JSONObject().put("command", "invalidate_network").put("network", JSONObject().put("handle", handle).put("generation", generation)))
    }
}
