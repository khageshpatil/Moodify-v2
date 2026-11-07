package com.moodify.app.data.social

import android.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A Moodify identity, derived entirely from a 32-byte seed. The seed is the
 * account: it never leaves the phone except as the 24-word recovery key.
 * Spec (shared with the server and web app): docs/social-protocol/identity.md
 */
class MoodifyIdentity private constructor(
    val seed: ByteArray,
    private val signPrivate: ByteArray,
    private val boxPrivate: ByteArray,
    val signPub: ByteArray,
    val boxPub: ByteArray,
    val code: String,
) {
    val signPubText: String get() = base64Url(signPub)
    val boxPubText: String get() = base64Url(boxPub)

    fun sign(message: String): ByteArray = Ed25519Signer().run {
        init(true, Ed25519PrivateKeyParameters(signPrivate, 0))
        val bytes = message.toByteArray(Charsets.UTF_8)
        update(bytes, 0, bytes.size)
        generateSignature()
    }

    fun signText(message: String): String = base64Url(sign(message))

    /**
     * End-to-end encryption to a friend (friends.md §3): AES-256-GCM under
     * HKDF(X25519(mine, theirs), salt "moodify-box-v1", info "codeA|codeB" sorted),
     * with "moodify-msg:v1:sender:recipient" as associated data.
     */
    fun sealFor(theirCode: String, theirBoxPub: ByteArray, plaintext: String, nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes)): SealedMessage {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pairKey(theirCode, theirBoxPub), javax.crypto.spec.GCMParameterSpec(128, nonce))
        cipher.updateAAD("moodify-msg:v1:$code:$theirCode".toByteArray())
        return SealedMessage(nonce, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Opens a copy of a message this identity sent (what another of your devices
     * sent, chat.md §2): the pair key is the same both ways, the AAD direction isn't.
     */
    fun openSent(theirCode: String, theirBoxPub: ByteArray, nonce: ByteArray, ciphertext: ByteArray): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, pairKey(theirCode, theirBoxPub), javax.crypto.spec.GCMParameterSpec(128, nonce))
        cipher.updateAAD("moodify-msg:v1:$code:$theirCode".toByteArray())
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** Opens a message a friend sent; throws if it was altered or isn't for this identity. */
    fun openFrom(theirCode: String, theirBoxPub: ByteArray, nonce: ByteArray, ciphertext: ByteArray): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, pairKey(theirCode, theirBoxPub), javax.crypto.spec.GCMParameterSpec(128, nonce))
        cipher.updateAAD("moodify-msg:v1:$theirCode:$code".toByteArray())
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun pairKey(theirCode: String, theirBoxPub: ByteArray): javax.crypto.SecretKey {
        val shared = ByteArray(32)
        org.bouncycastle.crypto.agreement.X25519Agreement().run {
            init(X25519PrivateKeyParameters(boxPrivate, 0))
            calculateAgreement(org.bouncycastle.crypto.params.X25519PublicKeyParameters(theirBoxPub, 0), shared, 0)
        }
        val (first, second) = listOf(code, theirCode).sorted()
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(shared, "moodify-box-v1".toByteArray(), "$first|$second".toByteArray()))
        val key = ByteArray(32).also { generator.generateBytes(it, 0, 32) }
        return javax.crypto.spec.SecretKeySpec(key, "AES")
    }

    companion object {
        private val SALT = "moodify-identity-v1".toByteArray()
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun create(random: SecureRandom = SecureRandom()): MoodifyIdentity =
            fromSeed(ByteArray(32).also(random::nextBytes))

        fun fromSeed(seed: ByteArray): MoodifyIdentity {
            require(seed.size == 32) { "seed must be 32 bytes" }
            val signPrivate = hkdf(seed, "sign")
            val boxPrivate = hkdf(seed, "box")
            val signPub = Ed25519PrivateKeyParameters(signPrivate, 0).generatePublicKey().encoded
            val boxPub = X25519PrivateKeyParameters(boxPrivate, 0).generatePublicKey().encoded
            return MoodifyIdentity(seed.copyOf(), signPrivate, boxPrivate, signPub, boxPub, codeFor(signPub))
        }

        /** MOOD-XXXX-XXXX: the first 40 bits of SHA-256(signing key) in Crockford base32. */
        fun codeFor(signPub: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(signPub)
            var bits = 0L
            for (i in 0 until 5) bits = (bits shl 8) or (digest[i].toLong() and 0xff)
            val chars = (7 downTo 0).map { CROCKFORD[((bits shr (it * 5)) and 31).toInt()] }.joinToString("")
            return "MOOD-${chars.take(4)}-${chars.drop(4)}"
        }

        /** What people type ("mood 7k3f 92qx") → MOOD-7K3F-92QX, or null. */
        fun normalizeCode(input: String): String? {
            val compact = input.uppercase().filter { it.isLetterOrDigit() }.removePrefix("MOOD")
                .replace('I', '1').replace('L', '1').replace('O', '0')
            if (compact.length != 8 || compact.any { it !in CROCKFORD }) return null
            return "MOOD-${compact.take(4)}-${compact.drop(4)}"
        }

        private fun hkdf(seed: ByteArray, info: String): ByteArray {
            val generator = HKDFBytesGenerator(SHA256Digest())
            generator.init(HKDFParameters(seed, SALT, info.toByteArray()))
            return ByteArray(32).also { generator.generateBytes(it, 0, 32) }
        }

        /**
         * Encrypts the identity [seed] to a new device's one-time X25519 key
         * (linking.md): HKDF(X25519(sender, device), salt "moodify-link-v1",
         * info "link:<id>") → AES-256-GCM with aad "moodify-link:v1:<id>".
         */
        fun sealSeedForLink(
            seed: ByteArray,
            devicePub: ByteArray,
            linkId: String,
            senderPrivate: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
            nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes),
        ): LinkSeal {
            val senderPub = X25519PrivateKeyParameters(senderPrivate, 0).generatePublicKey().encoded
            val shared = ByteArray(32)
            org.bouncycastle.crypto.agreement.X25519Agreement().run {
                init(X25519PrivateKeyParameters(senderPrivate, 0))
                calculateAgreement(org.bouncycastle.crypto.params.X25519PublicKeyParameters(devicePub, 0), shared, 0)
            }
            val generator = HKDFBytesGenerator(SHA256Digest())
            generator.init(HKDFParameters(shared, "moodify-link-v1".toByteArray(), "link:$linkId".toByteArray()))
            val key = ByteArray(32).also { generator.generateBytes(it, 0, 32) }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, nonce))
            cipher.updateAAD("moodify-link:v1:$linkId".toByteArray())
            return LinkSeal(senderPub, nonce, cipher.doFinal(seed))
        }

        /** The two words both screens show: bits of SHA-256(devicePub) into the BIP-39 list. */
        fun linkCheckWords(devicePub: ByteArray, wordlist: List<String>): List<String> {
            val digest = MessageDigest.getInstance("SHA-256").digest(devicePub)
            val bits = ((digest[0].toInt() and 0xff) shl 16) or ((digest[1].toInt() and 0xff) shl 8) or (digest[2].toInt() and 0xff)
            return listOf(wordlist[(bits shr 13) and 2047], wordlist[(bits shr 2) and 2047])
        }

        fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/** The 24-word recovery key (BIP-39, English): 256-bit seed + 8-bit checksum. */
class RecoveryWords(private val wordlist: List<String>) {
    init {
        require(wordlist.size == 2048) { "BIP-39 word list must have 2048 words" }
    }

    fun encode(seed: ByteArray): List<String> {
        require(seed.size == 32)
        val checksum = MessageDigest.getInstance("SHA-256").digest(seed)[0]
        val bits = (seed + checksum).joinToString("") { (it.toInt() and 0xff).toString(2).padStart(8, '0') }
        return (0 until 24).map { wordlist[bits.substring(it * 11, it * 11 + 11).toInt(2)] }
    }

    /** Seed from typed words; [RecoveryException] explains what's wrong in plain words. */
    fun decode(input: String): ByteArray {
        val words = input.trim().lowercase().split(Regex("""\s+""")).filter(String::isNotBlank)
        if (words.size != 24) throw RecoveryException("A recovery key has 24 words (you entered ${words.size}).")
        val bits = StringBuilder()
        words.forEachIndexed { position, word ->
            val index = wordlist.indexOf(word)
            if (index < 0) throw RecoveryException("Word ${position + 1}, \"$word\", isn't a recovery-key word.")
            bits.append(index.toString(2).padStart(11, '0'))
        }
        val seed = ByteArray(32) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(seed)[0].toInt() and 0xff
        if (expected != bits.substring(256).toInt(2)) throw RecoveryException("Those words have a typo somewhere.")
        return seed
    }
}

class RecoveryException(message: String) : Exception(message)

class SealedMessage(val nonce: ByteArray, val ciphertext: ByteArray)

class LinkSeal(val senderPub: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)
