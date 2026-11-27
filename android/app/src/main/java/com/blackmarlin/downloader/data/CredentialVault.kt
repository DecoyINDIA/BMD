package com.blackmarlin.downloader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.blackmarlin.downloader.domain.DownloadPolicy
import com.blackmarlin.downloader.ui.*
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Keystore stores a non-exportable key; only authenticated ciphertext is persisted here. */
class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("bmd-source-vault", Context.MODE_PRIVATE)
    private val alias = "bmd-source-credentials-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun save(id: String, credential: UiCredentialInput) {
        val source = DownloadPolicy.parse(credential.origin, false)
        DownloadPolicy.validateCredential(source, credential)
        if (credential.kind == CredentialKind.NONE) { checked(prefs.edit().remove(id).commit()); return }
        val clear = JSONObject().put("origin", source.origin).put("kind", credential.kind.name)
            .put("username", credential.username).put("secret", credential.secret).toString().toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(id.toByteArray()) }
            val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(clear), Base64.NO_WRAP)
            if (!prefs.edit().putString(id, encoded).commit()) throw BmdUiException("The source credential could not be saved.")
        } finally { clear.fill(0) }
    }
    private fun read(id: String): UiCredentialInput? {
        val encoded = prefs.getString(id, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(id.toByteArray())
            }
            val clear = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
            try {
                val json = JSONObject(clear.toString(Charsets.UTF_8))
                UiCredentialInput(json.getString("origin"), CredentialKind.valueOf(json.getString("kind")), json.optString("username"), json.getString("secret"))
            } finally { clear.fill(0); bytes.fill(0) }
        } catch (_: Exception) {
            prefs.edit().remove(id).commit()
            throw BmdUiException("This source credential is no longer available. Enter it again.", BmdUiException.Recovery.SOURCE_CREDENTIALS)
        }
    }
    @Synchronized fun saveTask(taskId: String, value: UiCredentialInput) = save("task:$taskId", value)
    private fun checked(ok: Boolean) { if (!ok) throw BmdUiException("Credential storage could not be updated. Try again.") }
    @Synchronized fun removeTask(taskId: String) { checked(prefs.edit().remove("task:$taskId").commit()) }
    @Synchronized fun removeOrphans(taskIds: Set<String>) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("task:") && it.removePrefix("task:") !in taskIds }.forEach(edit::remove)
        checked(edit.commit())
    }
    @Synchronized fun forTask(taskId: String, origin: String): UiCredentialInput? = read("task:$taskId")?.also {
        DownloadPolicy.requireSafe(it.origin == origin, "The stored source credential belongs to another origin.")
    }
    @Synchronized fun remember(value: UiCredentialInput) {
        val origin = DownloadPolicy.parse(value.origin, false).origin
        save("origin:${DownloadPolicy.digest(origin)}", value)
        val origins = prefs.getStringSet("origins", emptySet()).orEmpty().toMutableSet().apply { add(origin) }
        checked(prefs.edit().putStringSet("origins", origins).commit())
    }
    @Synchronized fun remembered(origin: String): UiCredentialInput? = read("origin:${DownloadPolicy.digest(origin)}")
    @Synchronized fun forget(origin: String) {
        val canonical = DownloadPolicy.parse(origin, false).origin
        checked(prefs.edit().remove("origin:${DownloadPolicy.digest(canonical)}")
            .putStringSet("origins", prefs.getStringSet("origins", emptySet()).orEmpty() - canonical).commit())
    }
    @Synchronized fun summaries(): List<UiCredentialSummary> = prefs.getStringSet("origins", emptySet()).orEmpty().mapNotNull { origin ->
        runCatching { remembered(origin)?.let { UiCredentialSummary(origin, it.kind) } }.getOrNull()
    }
    fun authorization(value: UiCredentialInput?): String? = when (value?.kind) {
        null, CredentialKind.NONE -> null
        CredentialKind.BASIC -> "Basic " + Base64.encodeToString("${value.username}:${value.secret}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        CredentialKind.BEARER -> "Bearer ${value.secret}"
    }
}
