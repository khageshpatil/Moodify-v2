package com.moodify.app.data.social

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the phone remembers about its Moodify identity. */
data class StoredIdentity(
    val identity: MoodifyIdentity,
    val displayName: String,
    /** The server knows this identity (false until the social server is reachable). */
    val registered: Boolean,
    /** The listener confirmed they wrote the recovery key down. */
    val backedUp: Boolean,
    /** Their picture, as the server last confirmed it. */
    val avatar: ProfileAvatar? = null,
)

/**
 * Keeps the identity seed encrypted with an AES key that lives in the Android
 * Keystore (it can't be copied off the phone). Excluded from Android backups:
 * a new phone gets the identity back from the 24-word recovery key.
 */
class IdentityStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("moodify_identity", Context.MODE_PRIVATE)

    fun load(): StoredIdentity? {
        val sealed = prefs.getString(KEY_SEED, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val seed = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(128, decode(iv)))
            cipher.doFinal(decode(sealed))
        }.getOrNull() ?: return null
        return StoredIdentity(
            identity = MoodifyIdentity.fromSeed(seed),
            displayName = prefs.getString(KEY_NAME, "").orEmpty(),
            registered = prefs.getBoolean(KEY_REGISTERED, false),
            backedUp = prefs.getBoolean(KEY_BACKED_UP, false),
            avatar = ProfileAvatar.parse(prefs.getString(KEY_AVATAR, null)),
        )
    }

    fun save(identity: MoodifyIdentity, displayName: String, backedUp: Boolean) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(identity.seed)
        prefs.edit()
            .putString(KEY_SEED, encode(sealed))
            .putString(KEY_IV, encode(cipher.iv))
            .putString(KEY_NAME, displayName)
            .putBoolean(KEY_REGISTERED, false)
            .putBoolean(KEY_BACKED_UP, backedUp)
            .commit()
    }

    fun setRegistered(registered: Boolean) = prefs.edit().putBoolean(KEY_REGISTERED, registered).apply()
    fun setBackedUp(backedUp: Boolean) = prefs.edit().putBoolean(KEY_BACKED_UP, backedUp).apply()
    fun setDisplayName(name: String) = prefs.edit().putString(KEY_NAME, name).apply()
    fun setAvatar(avatar: ProfileAvatar?) = prefs.edit().putString(KEY_AVATAR, avatar?.toJson()?.toString()).apply()

    /** Forgets the identity on this phone (the account itself is only removed by the server). */
    fun clear() {
        prefs.edit().clear().commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "moodify_identity_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SEED = "seed"
        const val KEY_IV = "iv"
        const val KEY_NAME = "display_name"
        const val KEY_REGISTERED = "registered"
        const val KEY_BACKED_UP = "backed_up"
        const val KEY_AVATAR = "avatar"
    }
}
