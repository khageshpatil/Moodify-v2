package com.moodify.app.data.social

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Same vectors as social/test/identity.test.mjs: Android, the server and the
 * web app must derive identical keys, codes and recovery words.
 */
class MoodifyIdentityTest {
    private val words = RecoveryWords(File("src/main/assets/bip39-english.txt").readLines().filter(String::isNotBlank))
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private data class Vector(val seed: String, val signPub: String, val boxPub: String, val code: String, val words: String)

    private val vectors = listOf(
        Vector(
            seed = "00".repeat(32),
            signPub = "EYsBlHrxePEKD7ngmtSa7jg8MKXsCsHYZC20i6zPdbc",
            boxPub = "07z1Ib3v3qu6imxXC_OZDNrx5mgF0frXwgtSJfeJa1g",
            code = "MOOD-CQHV-YGY4",
            words = "abandon ".repeat(23) + "art",
        ),
        Vector(
            seed = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            signPub = "kym6gtUG-avd0mU7zvp5gkhDp7Arl2-Eup33YxNEhL0",
            boxPub = "yDOz07GJVN7MAu4yizPb3sBBfdtCdqo5pjCVUwsPBE4",
            code = "MOOD-67AF-HHK3",
            words = "abandon amount liar amount expire adjust cage candy arch gather drum bullet absurd math era live bid rhythm alien crouch range attend journey unaware",
        ),
    )

    @Test
    fun seedsDeriveTheSameKeysCodeAndWordsAsTheServer() {
        vectors.forEach { v ->
            val identity = MoodifyIdentity.fromSeed(hex(v.seed))
            assertEquals(v.signPub, b64(identity.signPub))
            assertEquals(v.boxPub, b64(identity.boxPub))
            assertEquals(v.code, identity.code)
            assertEquals(v.words, words.encode(hex(v.seed)).joinToString(" "))
            assertArrayEquals(hex(v.seed), words.decode(v.words))
        }
    }

    @Test
    fun signaturesMatchTheServerReference() {
        // Ed25519 is deterministic: same key + message → the server's exact signature.
        val identity = MoodifyIdentity.fromSeed(hex(vectors[1].seed))
        val signature = identity.sign("moodify-login:v1:test-challenge-0001")
        assertEquals(
            "wYIaZ4gzdu7DNWFn502WW_xohqqlCRycDDjc-TAAIeE2kRYCr-92eL7Sxh8lHLzu6IwBLa3EcxIll5CcEGhwAw",
            b64(signature),
        )
        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(identity.signPub, 0)) }
        val message = "moodify-login:v1:test-challenge-0001".toByteArray()
        verifier.update(message, 0, message.size)
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun recoveryWordsExplainMistakes() {
        val good = vectors[1].words.split(" ")
        assertThrows(RecoveryException::class.java) { words.decode(good.take(23).joinToString(" ")) }
        val typo = assertThrows(RecoveryException::class.java) { words.decode((good.take(23) + "zebra").joinToString(" ")) }
        assertTrue(typo.message!!.contains("typo"))
        val unknown = assertThrows(RecoveryException::class.java) { words.decode((good.take(23) + "moodify").joinToString(" ")) }
        assertTrue(unknown.message!!.contains("Word 24"))
        assertArrayEquals(hex(vectors[1].seed), words.decode("  " + vectors[1].words.uppercase().replace(" ", "   ") + "\n"))
    }

    @Test
    fun codesAreNormalisedFromWhatPeopleType() {
        assertEquals("MOOD-67AF-HHK3", MoodifyIdentity.normalizeCode("mood-67af-hhk3"))
        assertEquals("MOOD-67AF-HHK3", MoodifyIdentity.normalizeCode(" 67AF HHK3 "))
        assertEquals("MOOD-0110-0000", MoodifyIdentity.normalizeCode("MOOD-0IL0-0000"))
        assertNull(MoodifyIdentity.normalizeCode("MOOD-67AF-HHK"))
        assertNull(MoodifyIdentity.normalizeCode("MOOD-67AF-HHKU"))
    }

    @Test
    fun messagesEncryptExactlyLikeTheServerReference() {
        val a = MoodifyIdentity.fromSeed(hex(vectors[0].seed))
        val b = MoodifyIdentity.fromSeed(hex(vectors[1].seed))
        val plaintext = """{"v":1,"type":"track","track":{"id":"JGwWNGJdvx8","title":"Shape of You","artist":"Ed Sheeran"}}"""
        val sealed = a.sealFor(b.code, b.boxPub, plaintext, nonce = ByteArray(12) { (it + 1).toByte() })
        assertEquals(
            "zwI4gG8-xcvFfq7dUM7Q8QtLynjZWv8pzPUZMiePwuUZmftS6BxqaecYXeen0Ym26zBZtQzMyBIydVUifi8octyXbEr5nu-uBO2Q7XPj-GOJcKrimfoq8JJjUtIneLyW6wOKm9bEq5Ebi3FoeG_vFA",
            b64(sealed.ciphertext),
        )
        assertEquals(plaintext, b.openFrom(a.code, a.boxPub, sealed.nonce, sealed.ciphertext))
        // Tampering or the wrong reader fails loudly.
        val tampered = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { b.openFrom(a.code, a.boxPub, sealed.nonce, tampered) }
        val stranger = MoodifyIdentity.fromSeed(ByteArray(32) { 9 })
        assertThrows(Exception::class.java) { stranger.openFrom(a.code, a.boxPub, sealed.nonce, sealed.ciphertext) }
    }

    @Test
    fun linkingSealsTheSeedExactlyLikeTheServerReference() {
        val devicePub = MoodifyIdentity.fromSeed(hex(vectors[0].seed)).boxPub
        val seal = MoodifyIdentity.sealSeedForLink(
            seed = hex(vectors[1].seed),
            devicePub = devicePub,
            linkId = "K7P2QXAB",
            senderPrivate = ByteArray(32) { 7 },
            nonce = ByteArray(12) { (12 - it).toByte() },
        )
        assertEquals("E75P6uryBMf9M1j8nAByGIHRdCeBKCJ-xnTzf3_pe20", b64(seal.senderPub))
        assertEquals("oT3rhHnBf5HDuqY1phRoklo3PqqDXeBomq9T7Tbd6sxKcWkJmOIBb5zvUNHExPGA", b64(seal.ciphertext))
        val wordlist = File("src/main/assets/bip39-english.txt").readLines().filter(String::isNotBlank)
        assertEquals(listOf("dream", "seven"), MoodifyIdentity.linkCheckWords(devicePub, wordlist))
    }
}
