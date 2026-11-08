package com.moodify.app.ui.social

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.moodify.app.AppGraph
import com.moodify.app.data.Track
import com.moodify.app.data.social.ChatMessageEntity
import com.moodify.app.data.social.FriendPresence
import com.moodify.app.data.social.SocialException
import com.moodify.app.data.social.SocialUser
import com.moodify.app.ui.navigation.ChatRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val peer: String,
    val friend: SocialUser? = null,
    val presence: FriendPresence? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val myCode: String? = null,
)

/** One conversation (social M5): messages, sending, retries and reactions. */
class ChatViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val social = AppGraph.social
    val peer: String = savedStateHandle.toRoute<ChatRoute>().code

    val state: StateFlow<ChatUiState> = combine(social.state, social.thread(peer)) { s, messages ->
        ChatUiState(
            peer = peer,
            friend = s.lists.friends.firstOrNull { it.code == peer },
            presence = s.presence[peer],
            messages = messages,
            myCode = s.myCode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(peer))

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** While the conversation is on screen it's read, and doesn't notify. */
    fun setVisible(visible: Boolean) = social.setActiveThread(if (visible) peer else null)

    fun send(text: String) = withFriend { social.sendText(it, text) }
    fun shareTrack(track: Track) = withFriend { social.sendTrack(it, track) }
    fun sendPlaylist(name: String, tracks: List<Track>) = withFriend { social.sendPlaylist(it, name, tracks) }
    fun retry(mid: String) = run { social.retry(mid) }
    fun react(mid: String, emoji: String) = withFriend { social.react(it, mid, emoji) }

    /** A playlist a friend sent: your own copy, in your library (it syncs to your other devices). */
    fun savePlaylist(name: String, tracks: List<Track>) = run {
        val store = AppGraph.playlistStore
        val copy = store.create(name)
        tracks.forEach { store.addTrack(copy.id, it) }
        _messages.send("Saved “$name” to your library")
    }

    private fun withFriend(block: suspend (SocialUser) -> Unit) = run {
        val friend = state.value.friend ?: throw SocialException("not_friends", "You can only message friends.")
        block(friend)
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocialException) {
                _messages.send(e.message ?: "Couldn't send. Try again.")
            } catch (e: Exception) {
                _messages.send("Couldn't send. Try again.")
            }
        }
    }

    override fun onCleared() {
        social.setActiveThread(null)
    }
}
