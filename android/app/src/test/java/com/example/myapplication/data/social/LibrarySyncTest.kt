package com.moodify.app.data.social

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Readable "encryption" for engine tests; the real one is [SyncKeys]. */
private val plainCrypto = object : RecordCrypto {
    override fun itemId(c: String, id: String) = "item:$c:$id"
    override fun seal(item: String, plaintext: String) = "n" to plaintext
    override fun open(item: String, nonce: String, ciphertext: String) = ciphertext
}

class LibrarySyncTest {
    // ── Crypto: the same bytes as social/src/client-keys.mjs (sync.md vectors) ──

    private val seed = ByteArray(32) { it.toByte() }

    @Test
    fun keysAndItemIdsMatchTheWeb() {
        val keys = SyncKeys.fromSeed(seed)
        assertEquals("6SXc6zIZRuplpVDrbXz84sn1FU-QM-Pf3_hfaJ8rrbI", B64Url.encode(keys.encKeyForTest()))
        assertEquals("Gn0COqdc4VTYX46UU0FCqXrCD3faxApzqryMDBtno-Y", B64Url.encode(keys.idKeyForTest()))
        assertEquals("b9lMfPj_poq_PRc_NPGcdQ", keys.itemId("like", "VAdGW7QDJiU"))
        assertNotEquals(keys.itemId("like", "VAdGW7QDJiU"), keys.itemId("playlist", "VAdGW7QDJiU"))
    }

    @Test
    fun opensWhatTheWebSealedAndSealsTheSameBytes() {
        val keys = SyncKeys.fromSeed(seed)
        val item = "b9lMfPj_poq_PRc_NPGcdQ"
        val nonce = "ZGVmZ2hpamtsbW5v"
        val ciphertext = "v8rUuREP07itPZGP6rWT5fVTE58qbtj6H8fk9btRXIdQw6t0y7pAiseeD_3Nm4UTaL6ABEuQa2zl8eLvtQF22JhrQO-6mGnVHGlkQbKbsz64igNvgHJWlSp98j8uYU2NLFlpzOCDsmSdbuE9EhawCJ00ET0U0B1g4GJwUkVVlJmCMsBM0g"
        val plaintext = """{"v":1,"c":"like","id":"VAdGW7QDJiU","data":{"track":{"id":"VAdGW7QDJiU","title":"Chaleya","artist":"Arijit Singh"}}}"""
        assertEquals(plaintext, keys.open("MOOD-TEST-0001", item, nonce, ciphertext))
        assertEquals(nonce to ciphertext, keys.seal("MOOD-TEST-0001", item, plaintext, B64Url.decode(nonce)))
        // Bound to the account and the item.
        assertTrue(runCatching { keys.open("MOOD-OTHR-0002", item, nonce, ciphertext) }.isFailure)
        assertTrue(runCatching { keys.open("MOOD-TEST-0001", "AAAAAAAAAAAAAAAAAAAAAA", nonce, ciphertext) }.isFailure)
    }

    @Test
    fun base64UrlRoundTrips() {
        for (n in 0..40) {
            val bytes = ByteArray(n) { (it * 37 + n).toByte() }
            assertTrue(bytes.contentEquals(B64Url.decode(B64Url.encode(bytes))))
        }
    }

    // ── The engine, against the server's rules in memory ────────────────────

    private class FakeServer : SyncTransport {
        val rows = LinkedHashMap<String, WireRecord>()
        var seq = 0L
        var pushes = 0
        override suspend fun pull(after: Long): SyncPage {
            val records = rows.values.filter { it.seq > after }.sortedBy { it.seq }
            return SyncPage(records, records.lastOrNull()?.seq ?: after, more = false)
        }
        override suspend fun push(records: List<WireRecord>): List<String> {
            pushes++
            return records.filter { r ->
                val existing = rows[r.item]
                if (existing != null && existing.updatedAt >= r.updatedAt) false
                else { rows[r.item] = r.copy(seq = ++seq); true }
            }.map { it.item }
        }
    }

    private class Device(override val collections: Set<String>, private val dropField: String? = null, private val mergeRecent: Boolean = false) : LibraryAdapter {
        val library = LinkedHashMap<String, Any>()
        var state = SyncState()
        override val mergeable get() = if (mergeRecent) setOf("recent") else emptySet()
        override fun merge(c: String, local: Any?, remote: Any?) = SyncWire.mergePlays(local, remote)
        override suspend fun snapshot(): Map<String, Any> = LinkedHashMap(library)
        override suspend fun apply(changes: List<SyncChange>) {
            for (ch in changes) {
                val key = "${ch.c}:${ch.id}"
                val data = ch.data
                if (data == null) library.remove(key)
                else library[key] = if (dropField != null && data is JSONObject) JSONObject(data.toString()).apply { remove(dropField) } else data
            }
        }
        fun sync(server: FakeServer, now: () -> Long) = runBlocking {
            state = LibrarySync.syncOnce(this@Device, server, plainCrypto, state, now).state
        }
    }

    private val all = setOf("like", "playlist", "saved", "recent")
    private var clock = 1_000L
    private val tick = { ++clock }
    private fun obj(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun likesAndUnlikesReachTheOtherDevice() {
        val server = FakeServer()
        val phone = Device(all)
        val web = Device(all)
        phone.library["like:abc"] = obj("track" to obj("id" to "abc", "title" to "Chaleya"))
        phone.sync(server, tick); web.sync(server, tick)
        assertEquals("Chaleya", (web.library["like:abc"] as JSONObject).getJSONObject("track").getString("title"))
        web.library.remove("like:abc")
        web.sync(server, tick); phone.sync(server, tick)
        assertFalse(phone.library.containsKey("like:abc"))
    }

    @Test
    fun offlineEditsOnBothDevicesEndUpOnBoth() {
        val server = FakeServer()
        val phone = Device(all)
        val web = Device(all)
        phone.library["playlist:p1"] = obj("name" to "Road trip")
        web.library["like:xyz"] = obj("track" to obj("id" to "xyz"))
        phone.sync(server, tick); web.sync(server, tick); phone.sync(server, tick)
        assertEquals(setOf("like:xyz", "playlist:p1"), phone.library.keys)
        assertEquals(setOf("like:xyz", "playlist:p1"), web.library.keys)
    }

    @Test
    fun theNewestEditWins() {
        val server = FakeServer()
        val phone = Device(all)
        val web = Device(all)
        phone.library["playlist:p1"] = obj("name" to "v1")
        phone.sync(server, tick); web.sync(server, tick)
        phone.library["playlist:p1"] = obj("name" to "phone rename")
        val earlier = tick()
        web.library["playlist:p1"] = obj("name" to "web rename")
        web.sync(server, tick)
        phone.sync(server) { earlier }
        assertEquals("web rename", (phone.library["playlist:p1"] as JSONObject).getString("name"))
    }

    @Test
    fun differentRepresentationsDontBounce() {
        val server = FakeServer()
        val phone = Device(all)
        val web = Device(all, dropField = "pinned")
        phone.library["playlist:p1"] = obj("name" to "Gym", "pinned" to true)
        phone.sync(server, tick); web.sync(server, tick)
        val pushes = server.pushes
        repeat(3) { web.sync(server, tick); phone.sync(server, tick) }
        assertEquals(pushes, server.pushes)
        assertTrue((phone.library["playlist:p1"] as JSONObject).getBoolean("pinned"))
    }

    @Test
    fun unmanagedCollectionsAreLeftAlone() {
        val server = FakeServer()
        val phone = Device(all)
        val web = Device(setOf("like", "playlist", "recent"))
        phone.library["saved:album:MPREb_1"] = obj("kind" to "album", "title" to "Aashiqui 2")
        phone.sync(server, tick); web.sync(server, tick); phone.sync(server, tick)
        assertFalse(web.library.containsKey("saved:album:MPREb_1"))
        assertTrue(phone.library.containsKey("saved:album:MPREb_1"))
    }

    @Test
    fun recentPlaysMerge() {
        fun plays(vararg p: Pair<String, Long>) = obj("plays" to JSONArray(p.map { (id, at) -> obj("track" to obj("id" to id), "playedAt" to at) }))
        val server = FakeServer()
        val phone = Device(all, mergeRecent = true)
        val web = Device(all, mergeRecent = true)
        phone.library["recent:plays"] = plays("a" to 5L, "b" to 3L)
        web.library["recent:plays"] = plays("c" to 4L, "d" to 1L)
        phone.sync(server, tick); web.sync(server, tick); phone.sync(server, tick)
        fun times(d: Device) = (d.library["recent:plays"] as JSONObject).getJSONArray("plays").let { a -> (0 until a.length()).map { a.getJSONObject(it).getLong("playedAt") } }
        assertEquals(listOf(5L, 4L, 3L, 1L), times(web))
        assertEquals(listOf(5L, 4L, 3L, 1L), times(phone))
        val pushes = server.pushes
        web.sync(server, tick); phone.sync(server, tick)
        assertEquals(pushes, server.pushes)
    }

    @Test
    fun stateSurvivesSaving() {
        val state = SyncState(42, mapOf("like:a" to IndexEntry("i1", 5, "h"), "playlist:p:1" to IndexEntry("i2", 6, "deleted", deleted = true)))
        assertEquals(state, SyncState.fromJson(state.toJson()))
        assertEquals(SyncState(), SyncState.fromJson("not json"))
    }
}
