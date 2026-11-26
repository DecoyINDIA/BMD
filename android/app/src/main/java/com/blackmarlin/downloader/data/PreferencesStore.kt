package com.blackmarlin.downloader.data

import android.content.Context
import com.blackmarlin.downloader.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("bmd-preferences", Context.MODE_PRIVATE)
    val state = MutableStateFlow(read())
    private fun read(): UiSettings = runCatching {
        val j = JSONObject(prefs.getString("settings", "{}") ?: "{}")
        UiSettings(
            defaultNetwork = NetworkPolicy.valueOf(j.optString("network", "UNMETERED")),
            maxActiveTransfers = j.optInt("active", 2).coerceIn(1, 2),
            segmentsPerTransfer = j.optInt("segments", 2).coerceIn(1, 2),
            globalSegmentLimit = j.optInt("global", 4).coerceIn(1, 4),
            perHostLimit = j.optInt("host", 2).coerceIn(1, 2),
            speedLimitBytesPerSecond = j.optLong("limit", 0).coerceAtLeast(0),
            theme = ThemeMode.valueOf(j.optString("theme", "SYSTEM")),
            reducedMotion = j.optBoolean("motion", false),
            privateNotifications = j.optBoolean("private", true),
            defaultDestination = UiDestination(
                DestinationKind.valueOf(j.optString("destinationKind", "PUBLIC_DOWNLOADS")),
                j.optString("destinationName", "Downloads"), j.optString("treeUri").ifEmpty { null },
            ),
        )
    }.getOrDefault(UiSettings())
    fun update(input: UiSettings) {
        val value = input.copy(maxActiveTransfers = input.maxActiveTransfers.coerceIn(1, 2), segmentsPerTransfer = input.segmentsPerTransfer.coerceIn(1, 2), globalSegmentLimit = input.globalSegmentLimit.coerceIn(1, 4), perHostLimit = input.perHostLimit.coerceIn(1, 2), speedLimitBytesPerSecond = input.speedLimitBytesPerSecond.coerceAtLeast(0))
        val j = JSONObject().put("network", value.defaultNetwork.name).put("active", value.maxActiveTransfers)
            .put("segments", value.segmentsPerTransfer).put("global", value.globalSegmentLimit).put("host", value.perHostLimit)
            .put("limit", value.speedLimitBytesPerSecond).put("theme", value.theme.name).put("motion", value.reducedMotion)
            .put("private", value.privateNotifications).put("destinationKind", value.defaultDestination.kind.name)
            .put("destinationName", value.defaultDestination.displayName).put("treeUri", value.defaultDestination.treeUri)
        if (!prefs.edit().putString("settings", j.toString()).commit()) throw BmdUiException("The settings could not be saved.")
        state.value = value
    }
}
