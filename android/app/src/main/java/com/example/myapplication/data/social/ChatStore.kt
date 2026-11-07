package com.moodify.app.data.social

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * One chat message, decrypted, kept only on this phone (docs/social-protocol/chat.md).
 * [mid] is the sender's id for the message, the same on every device, so
 * copies and retries never show twice and reactions can point at it.
 */
@Entity(tableName = "chat_messages", indices = [Index(value = ["peer", "sentAtMs"])])
data class ChatMessageEntity(
    @PrimaryKey val mid: String,
    /** The friend this conversation is with. */
    val peer: String,
    val fromMe: Boolean,
    /** text, track, collection or room. */
    val kind: String,
    /** The text, or the share payload's JSON for cards. */
    val body: String,
    val sentAtMs: Long,
    /** sending, sent, failed (mine) or received (theirs). */
    val status: String,
    /** JSON object: Moodify code → emoji. */
    val reactions: String = "",
    val read: Boolean = false,
)

data class ConversationRow(
    @Embedded val last: ChatMessageEntity,
    val unread: Int,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE peer = :peer ORDER BY sentAtMs, rowid")
    fun observeThread(peer: String): Flow<List<ChatMessageEntity>>

    @Query(
        """SELECT m.*, (SELECT COUNT(*) FROM chat_messages u WHERE u.peer = m.peer AND u.fromMe = 0 AND u.read = 0) AS unread
           FROM chat_messages m
           WHERE m.mid = (SELECT x.mid FROM chat_messages x WHERE x.peer = m.peer ORDER BY x.sentAtMs DESC, x.rowid DESC LIMIT 1)
           ORDER BY m.sentAtMs DESC""",
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE fromMe = 0 AND read = 0")
    fun observeUnreadTotal(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE mid = :mid")
    suspend fun get(mid: String): ChatMessageEntity?

    @Query("UPDATE chat_messages SET status = :status WHERE mid = :mid")
    suspend fun setStatus(mid: String, status: String)

    @Query("UPDATE chat_messages SET reactions = :reactions WHERE mid = :mid")
    suspend fun setReactions(mid: String, reactions: String)

    @Query("UPDATE chat_messages SET read = 1 WHERE peer = :peer AND fromMe = 0 AND read = 0")
    suspend fun markRead(peer: String)

    @Query("DELETE FROM chat_messages WHERE peer = :peer")
    suspend fun deleteThread(peer: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    @Query("SELECT * FROM chat_messages WHERE fromMe = 1 AND status = 'sending' AND sentAtMs < :before")
    suspend fun stuckSending(before: Long): List<ChatMessageEntity>
}

/** Reactions: one emoji per person per message; an empty emoji removes yours. */
object Reactions {
    fun parse(json: String): Map<String, String> = runCatching {
        if (json.isBlank()) emptyMap() else JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }.getOrDefault(emptyMap())

    fun apply(json: String, who: String, emoji: String): String {
        val map = parse(json).toMutableMap()
        if (emoji.isBlank()) map.remove(who) else map[who] = emoji
        return if (map.isEmpty()) "" else JSONObject(map as Map<*, *>).toString()
    }

    /** The quick picks offered on a long press. */
    val QUICK = listOf("❤️", "😂", "🔥", "👍", "😮", "😢")
}
