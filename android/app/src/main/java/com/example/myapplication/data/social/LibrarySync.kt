package com.moodify.app.data.social

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Library sync between your devices (docs/social-protocol/sync.md), the same
 * logic as the web's src/aura/social/syncEngine.ts. One pass: pull what
 * changed since this device's position and apply it, then compare the library
 * with what was last synced and push the differences. The newest change wins
 * per item. Data values are org.json values (JSONObject, JSONArray, String,
 * numbers, booleans).
 */
data class SyncChange(
    val c: String,
    val id: String,
    /** null: the item was deleted. */
    val data: Any?,
    /** When the change was made (ms); an item's "added at" when the data has none. */
    val updatedAt: Long,
)

interface LibraryAdapter {
    /** Collections this device syncs; records of other kinds are left alone. */
    val collections: Set<String>
    /** Collections merged instead of replaced (recent plays). */
    val mergeable: Set<String> get() = emptySet()
    fun merge(c: String, local: Any?, remote: Any?): Any? = remote
    /** The library now, in the wire format, by "collection:id". */
    suspend fun snapshot(): Map<String, Any>
    suspend fun apply(changes: List<SyncChange>)
}

data class WireRecord(val item: String, val updatedAt: Long, val nonce: String, val ciphertext: String, val seq: Long = 0)
data class SyncPage(val records: List<WireRecord>, val latest: Long, val more: Boolean)

interface SyncTransport {
    suspend fun pull(after: Long): SyncPage
    /** Returns the items the server accepted (an older version than the server's is ignored). */
    suspend fun push(records: List<WireRecord>): List<String>
}

interface RecordCrypto {
    fun itemId(c: String, id: String): String
    /** (nonce, ciphertext) */
    fun seal(item: String, plaintext: String): Pair<String, String>
    fun open(item: String, nonce: String, ciphertext: String): String
}

data class IndexEntry(val item: String, val updatedAt: Long, val hash: String, val deleted: Boolean = false)

data class SyncState(val cursor: Long = 0, val index: Map<String, IndexEntry> = emptyMap()) {
    fun toJson(): String = JSONObject()
        .put("cursor", cursor)
        .put("index", JSONObject().apply {
            index.forEach { (key, e) ->
                put(key, JSONObject().put("item", e.item).put("updatedAt", e.updatedAt).put("hash", e.hash).apply { if (e.deleted) put("deleted", true) })
            }
        })
        .toString()

    companion object {
        fun fromJson(text: String?): SyncState = runCatching {
            val json = JSONObject(text ?: return SyncState())
            val index = json.optJSONObject("index") ?: JSONObject()
            SyncState(
                cursor = json.optLong("cursor"),
                index = index.keys().asSequence().associateWith { key ->
                    val e = index.getJSONObject(key)
                    IndexEntry(e.getString("item"), e.getLong("updatedAt"), e.getString("hash"), e.optBoolean("deleted"))
                },
            )
        }.getOrDefault(SyncState())
    }
}

data class SyncResult(val state: SyncState, val pulled: Int, val pushed: Int)

object LibrarySync {
    const val PUSH_BATCH = 100
    private const val DELETED = "deleted"

    /** JSON with sorted keys (nulls dropped), so equal data hashes equally. */
    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().filter { !value.isNull(it) }.sorted()
            .joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.get(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is Map<*, *> -> canonical(JSONObject(value))
        is List<*> -> canonical(JSONArray(value))
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> if (value.toDouble() == Math.floor(value.toDouble()) && !value.toDouble().isInfinite()) value.toLong().toString() else value.toString()
        else -> JSONObject.quote(value.toString())
    }

    /** Only compared on this device. */
    fun hash(value: Any?): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    private fun split(key: String): Pair<String, String> = key.substringBefore(':') to key.substringAfter(':')

    suspend fun syncOnce(
        adapter: LibraryAdapter,
        transport: SyncTransport,
        crypto: RecordCrypto,
        state: SyncState,
        now: () -> Long = System::currentTimeMillis,
        onState: (SyncState) -> Unit = {},
    ): SyncResult {
        val managed = adapter.collections
        var cursor = state.cursor
        val index = state.index.toMutableMap()
        var pulled = 0
        fun snapshotState() = SyncState(cursor, index.toMap())

        // ── 1. Pull and apply ───────────────────────────────────────────────
        suspend fun pullAll() {
            repeat(200) {
                val page = transport.pull(cursor)
                val changes = mutableListOf<SyncChange>()
                val touched = mutableListOf<Triple<String, String, Boolean>>() // key, remote hash, merged
                val local = adapter.snapshot()
                for (r in page.records) {
                    val plain = runCatching { JSONObject(crypto.open(r.item, r.nonce, r.ciphertext)) }.getOrNull() ?: continue
                    if (plain.optInt("v") != 1) continue
                    val c = plain.optString("c")
                    val id = plain.optString("id")
                    if (c.isEmpty() || c !in managed) continue
                    val key = "$c:$id"
                    val known = index[key]
                    if (known != null && r.updatedAt <= known.updatedAt) continue // our own echo, or older
                    if (plain.optBoolean("deleted")) {
                        index[key] = IndexEntry(r.item, r.updatedAt, DELETED, deleted = true)
                        // Something this device has but never synced isn't someone else's to delete.
                        if (known == null && local.containsKey(key)) continue
                        changes += SyncChange(c, id, null, r.updatedAt)
                        continue
                    }
                    val remote = plain.opt("data")
                    val merged = c in adapter.mergeable
                    val data = if (merged) adapter.merge(c, local[key], remote) else remote
                    changes += SyncChange(c, id, data, r.updatedAt)
                    index[key] = IndexEntry(r.item, r.updatedAt, "")
                    touched += Triple(key, hash(remote), merged)
                    pulled++
                }
                if (changes.isNotEmpty()) adapter.apply(changes)
                // Record what this library now holds for those items, so a slightly
                // different local representation isn't pushed back as a "change".
                if (touched.isNotEmpty()) {
                    val after = adapter.snapshot()
                    for ((key, remoteHash, merged) in touched) {
                        val entry = index.getValue(key)
                        val mine = after[key]
                        index[key] = if (mine == null) entry.copy(hash = DELETED, deleted = true) else {
                            val mineHash = hash(mine)
                            // A merge that added plays of ours: leave it "changed" so it's pushed.
                            entry.copy(hash = if (merged && mineHash != remoteHash) remoteHash else mineHash)
                        }
                    }
                }
                cursor = maxOf(cursor, page.latest)
                onState(snapshotState())
                if (!page.more) return
            }
        }
        pullAll()

        // ── 2. Compare and push ─────────────────────────────────────────────
        data class Out(val key: String, val plain: JSONObject, val hash: String, val deleted: Boolean)
        val snapshot = adapter.snapshot()
        val outgoing = mutableListOf<Out>()
        for ((key, data) in snapshot) {
            val (c, id) = split(key)
            if (c !in managed) continue
            val h = hash(data)
            if (index[key]?.hash == h) continue
            outgoing += Out(key, JSONObject().put("v", 1).put("c", c).put("id", id).put("data", data), h, false)
        }
        for ((key, entry) in index.toMap()) {
            val (c, id) = split(key)
            if (c !in managed || entry.deleted || snapshot.containsKey(key)) continue
            outgoing += Out(key, JSONObject().put("v", 1).put("c", c).put("id", id).put("deleted", true), DELETED, true)
        }
        var pushed = 0
        var rejected = false
        for (batch in outgoing.chunked(PUSH_BATCH)) {
            val meta = mutableMapOf<String, Pair<Out, Long>>()
            val records = batch.map { o ->
                val (c, id) = split(o.key)
                val item = index[o.key]?.item ?: crypto.itemId(c, id)
                val updatedAt = maxOf(now(), (index[o.key]?.updatedAt ?: 0L) + 1)
                val (nonce, ciphertext) = crypto.seal(item, o.plain.toString())
                meta[item] = o to updatedAt
                WireRecord(item, updatedAt, nonce, ciphertext)
            }
            val accepted = transport.push(records)
            for (item in accepted) {
                val (o, updatedAt) = meta[item] ?: continue
                index[o.key] = IndexEntry(item, updatedAt, o.hash, o.deleted)
                pushed++
            }
            if (accepted.size < records.size) rejected = true
            onState(snapshotState())
        }
        // Something newer was already there: fetch it.
        if (rejected) pullAll()
        return SyncResult(snapshotState(), pulled, pushed)
    }
}
