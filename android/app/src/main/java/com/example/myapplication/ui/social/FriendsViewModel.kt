package com.moodify.app.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moodify.app.AppGraph
import com.moodify.app.data.CatalogItem
import com.moodify.app.data.Track
import com.moodify.app.data.social.RoomUiState
import com.moodify.app.data.social.SocialException
import com.moodify.app.data.social.SocialState
import com.moodify.app.data.social.SocialUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Friends tab + "Send to a friend": thin layer over the process-wide SocialRepository. */
class FriendsViewModel : ViewModel() {
    private val social = AppGraph.social
    val state: StateFlow<SocialState> = social.state

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-off confirmations and errors for the snackbar. */
    val messages = _messages.receiveAsFlow()

    fun refresh() = run { social.refresh() }

    fun addFriend(code: String, note: String) = run { say(social.addFriend(code, note.takeIf(String::isNotBlank))) }

    fun accept(user: SocialUser) = run { social.answer(user.code, accept = true); say("You and ${user.displayName} are now friends.") }

    fun ignore(user: SocialUser) = run { social.answer(user.code, accept = false) }

    fun remove(user: SocialUser) = run { social.remove(user.code); say("Removed ${user.displayName}.") }

    fun block(user: SocialUser) = run { social.block(user.code); say("Blocked ${user.displayName}. They won't be told.") }

    fun report(user: SocialUser, reason: String) = run { social.report(user.code, reason); say("Thanks. We'll look into it.") }

    fun sendTrack(to: SocialUser, track: Track) = run { social.sendTrack(to, track); say("Sent to ${to.displayName}") }

    fun sendCollection(to: SocialUser, item: CatalogItem) = run { social.sendCollection(to, item); say("Sent to ${to.displayName}") }

    fun dismissShared(id: Long) = social.dismissShared(id)

    fun sendPlaylist(to: SocialUser, name: String, tracks: List<Track>) =
        run { social.sendPlaylist(to, name, tracks); say("Sent “$name” to ${to.displayName}") }

    /** Opens a room and invites [friend] to it. */
    fun listenWith(friend: SocialUser) = run {
        rooms.create()
        rooms.invite(friend)
        say("Invited ${friend.displayName} to listen together")
    }

    // ── Listen together (M4) ────────────────────────────────────────────────

    private val rooms = AppGraph.rooms
    val room: StateFlow<RoomUiState?> = rooms.room

    fun startRoom() = run { val id = rooms.create(); say("Room ${id.chunked(3).joinToString(" ")} is open. Invite friends.") }
    fun joinRoom(roomId: String) = rooms.join(roomId)
    fun leaveRoom() = rooms.leave()
    fun followFriend(friend: SocialUser) = rooms.followFriend(friend.code, friend.displayName)
    fun roomChat(text: String) = rooms.chat(text)
    fun makeDj(code: String) = rooms.makeDj(code)
    fun makeHost(code: String) = rooms.makeHost(code)
    fun roomNext() = rooms.next()
    fun queueInRoom(track: Track) = rooms.queue(track)
    fun inviteToRoom(friend: SocialUser) = run { rooms.invite(friend); say("Invited ${friend.displayName}") }

    private suspend fun say(message: String) = _messages.send(message)

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocialException) {
                say(e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                say("Something went wrong. Try again.")
            }
        }
    }
}
