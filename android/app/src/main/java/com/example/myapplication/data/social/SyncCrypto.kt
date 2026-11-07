package com.moodify.app.data.social

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Library sync keys (docs/social-protocol/sync.md), the same as
 * social/src/client-keys.mjs: HKDF(seed, "sync-enc" / "sync-id") with the
 * identity salt. Records are AES-256-GCM with aad
 * "moodify-sync:v1:<code>:<item>"; an item's id is HMAC-SHA256(idKey,
 * "collection\nid"), first 16 bytes, base64url.
 */
class SyncKeys(private val enc: ByteArray, private val id: ByteArray) {
    fun itemId(collection: String, itemId: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(id, "HmacSHA256")) }
        return B64Url.encode(mac.doFinal("$collection\n$itemId".toByteArray()).copyOf(16))
    }

    /** Returns (nonce, ciphertext), both base64url. */
    fun seal(code: String, item: String, plaintext: String, nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes)): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(code, item))
        return B64Url.encode(nonce) to B64Url.encode(cipher.doFinal(plaintext.toByteArray()))
    }

    /** Throws if the record was altered, moved to another item, or isn't yours. */
    fun open(code: String, item: String, nonce: String, ciphertext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(enc, "AES"), GCMParameterSpec(128, B64Url.decode(nonce)))
        cipher.updateAAD(aad(code, item))
        return String(cipher.doFinal(B64Url.decode(ciphertext)))
    }

    internal fun encKeyForTest() = enc.copyOf()
    internal fun idKeyForTest() = id.copyOf()

    private fun aad(code: String, item: String) = "moodify-sync:v1:$code:$item".toByteArray()

    companion object {
        fun fromSeed(seed: ByteArray): SyncKeys {
            require(seed.size == 32) { "seed must be 32 bytes" }
            return SyncKeys(MoodifyIdentity.hkdf(seed, "sync-enc"), MoodifyIdentity.hkdf(seed, "sync-id"))
        }
    }
}

/** base64url without padding, in plain Kotlin (android.util.Base64 isn't in unit tests; java.util.Base64 needs API 26). */
object B64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) out.append(ALPHABET[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) out.append(ALPHABET[b2 and 63])
            i += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "not base64url" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
