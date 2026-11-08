package com.moodify.app.ui.social

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moodify.app.BuildConfig
import com.moodify.app.data.social.IdentityStore
import com.moodify.app.data.social.MoodifyIdentity
import com.moodify.app.data.social.RecoveryException
import com.moodify.app.data.social.RecoveryWords
import com.moodify.app.data.social.SocialClient
import com.moodify.app.data.social.SocialException
import com.moodify.app.data.social.SocialSession
import com.moodify.app.data.social.StoredIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val loading: Boolean = true,
    val code: String? = null,
    val displayName: String = "",
    val avatar: com.moodify.app.data.social.ProfileAvatar? = null,
    val registered: Boolean = false,
    val backedUp: Boolean = false,
    val serverConfigured: Boolean = false,
    val busy: Boolean = false,
    /** Shown once, right after creating an identity, until the words are confirmed. */
    val recoveryWords: List<String>? = null,
    /** A browser waiting to be linked: its code, one-time key and the two check words. */
    val pendingLink: PendingLink? = null,
    val error: String? = null,
    val notice: String? = null,
)

data class PendingLink(val linkId: String, val devicePub: ByteArray, val words: List<String>)

/** Your Moodify code: create, back up, restore, rename, delete (social M1). */
class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val store = IdentityStore(application)
    private val client = SocialClient(BuildConfig.SOCIAL_BASE_URL)
    private val wordlist by lazy {
        application.assets.open("bip39-english.txt").bufferedReader().readLines().filter(String::isNotBlank)
    }
    private val words by lazy { RecoveryWords(wordlist) }
    private var session: SocialSession? = null

    private val _state = MutableStateFlow(AccountUiState(serverConfigured = client.isConfigured))
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { store.load() }
            publish(stored)
            if (stored != null) connect(stored, quiet = true)
        }
    }

    private fun notifySocial() = com.moodify.app.AppGraph.social.identityChanged()

    fun create(displayName: String) = work {
        val name = cleanName(displayName) ?: return@work fail("Pick a name with 2–24 characters.")
        var identity = MoodifyIdentity.create()
        withContext(Dispatchers.IO) { store.save(identity, name, backedUp = false) }
        val stored = store.load() ?: return@work fail("Couldn't save your identity on this phone.")
        _state.update { it.copy(recoveryWords = words.encode(identity.seed)) }
        publish(stored)
        // 40-bit codes can (very rarely) collide; a fresh seed fixes it.
        if (!connect(stored, quiet = false, onCodeTaken = {
                identity = MoodifyIdentity.create()
                withContext(Dispatchers.IO) { store.save(identity, name, backedUp = false) }
                _state.update { it.copy(recoveryWords = words.encode(identity.seed)) }
                store.load()
            })
        ) return@work
        notifySocial()
    }

    fun restore(recoveryKey: String, displayName: String) = work {
        val seed = try {
            words.decode(recoveryKey)
        } catch (e: RecoveryException) {
            return@work fail(e.message ?: "Those words don't look right.")
        }
        val identity = MoodifyIdentity.fromSeed(seed)
        // Only used if the server doesn't know this identity yet.
        val name = cleanName(displayName) ?: "Moodify listener"
        withContext(Dispatchers.IO) { store.save(identity, name, backedUp = true) }
        val stored = store.load() ?: return@work fail("Couldn't save your identity on this phone.")
        publish(stored)
        connect(stored, quiet = false)
        notifySocial()
        _state.update { it.copy(notice = "Welcome back, ${it.displayName}.") }
    }

    /** The listener wrote the words down (checked by asking for two of them). */
    fun confirmBackedUp() {
        store.setBackedUp(true)
        _state.update { it.copy(backedUp = true, recoveryWords = null, notice = "Recovery key saved. Keep it somewhere safe.") }
    }

    /** Shows the recovery key again (Settings › Account › Recovery key). */
    fun revealRecoveryWords() {
        val stored = store.load() ?: return
        _state.update { it.copy(recoveryWords = words.encode(stored.identity.seed)) }
    }

    fun hideRecoveryWords() = _state.update { it.copy(recoveryWords = null) }

    fun rename(displayName: String) = work {
        val name = cleanName(displayName) ?: return@work fail("Pick a name with 2–24 characters.")
        val stored = store.load() ?: return@work
        if (stored.registered) {
            val token = ensureSession(stored) ?: return@work
            client.rename(token, name)
        }
        store.setDisplayName(name)
        publish(store.load())
        com.moodify.app.AppGraph.social.profileChanged()
    }

    /** Your picture: an emoji on a colour, a profile song, or null to remove it. */
    fun setAvatar(avatar: com.moodify.app.data.social.ProfileAvatar?) = work {
        val stored = store.load() ?: return@work
        if (!stored.registered && !connect(stored, quiet = false)) return@work
        val token = ensureSession(store.load() ?: return@work) ?: return@work
        store.setAvatar(client.setAvatar(token, avatar))
        publish(store.load())
        com.moodify.app.AppGraph.social.profileChanged()
        _state.update { it.copy(notice = if (avatar == null) "Picture removed." else "Picture saved. Friends see it now.") }
    }

    fun retryConnect() = work {
        store.load()?.let { connect(it, quiet = false) }
    }

    /** Deletes the account everywhere: on the server (if it knows it) and on this phone. */
    fun deleteAccount() = work {
        val stored = store.load() ?: return@work
        if (stored.registered) {
            val token = ensureSession(stored) ?: return@work
            client.deleteAccount(token)
        }
        session = null
        withContext(Dispatchers.IO) { store.clear() }
        notifySocial()
        _state.value = AccountUiState(loading = false, serverConfigured = client.isConfigured, notice = "Your account was deleted.")
    }

    /**
     * Step 1 of linking a browser: find its one-time key (from the QR code, or
     * from the server for a typed code) and show the two words to compare.
     */
    fun prepareLink(code: String, devicePubFromQr: ByteArray? = null) = work {
        val linkId = code.uppercase().filter { it.isLetterOrDigit() }
        if (linkId.length != 8) return@work fail("Link codes have 8 characters, like K7P2-QXAB.")
        val devicePub = devicePubFromQr?.takeIf { it.size == 32 } ?: client.linkDeviceKey(linkId)
        _state.update { it.copy(pendingLink = PendingLink(linkId, devicePub, MoodifyIdentity.linkCheckWords(devicePub, wordlist))) }
    }

    /** Step 2: the words matched, so send this identity, encrypted to that browser. */
    fun confirmLink() = work {
        val link = _state.value.pendingLink ?: return@work
        val stored = store.load() ?: return@work fail("Create your Moodify code first.")
        val token = ensureSession(stored) ?: return@work
        client.sendLinkSeal(token, link.linkId, MoodifyIdentity.sealSeedForLink(stored.identity.seed, link.devicePub, link.linkId))
        _state.update { it.copy(pendingLink = null, notice = "Linked. Your browser is now signed in as you.") }
    }

    fun cancelLink() = _state.update { it.copy(pendingLink = null) }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Signs in, registering first when the server doesn't know this identity.
     * Returns false after reporting an error. [quiet] skips "offline" errors.
     */
    private suspend fun connect(
        stored: StoredIdentity,
        quiet: Boolean,
        onCodeTaken: (suspend () -> StoredIdentity?)? = null,
    ): Boolean {
        if (!client.isConfigured) return true
        return try {
            val signedIn = client.login(stored.identity) ?: client.register(stored.identity, stored.displayName)
            session = signedIn
            store.setRegistered(true)
            // The server's name wins: a restored phone only had a placeholder.
            if (signedIn.displayName != stored.displayName) store.setDisplayName(signedIn.displayName)
            if (signedIn.avatar != stored.avatar) store.setAvatar(signedIn.avatar)
            publish(store.load())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocialException) {
            if (e.errorCode == "code_taken" && onCodeTaken != null) {
                val fresh = onCodeTaken() ?: return false
                return connect(fresh, quiet, onCodeTaken = null)
            }
            if (!quiet || e.errorCode != "offline") fail(e.message ?: "Couldn't connect.")
            false
        }
    }

    private suspend fun ensureSession(stored: StoredIdentity): String? {
        val now = System.currentTimeMillis() / 1000
        session?.takeIf { it.expiresAtSeconds > now + 60 }?.let { return it.token }
        return if (connect(stored, quiet = false)) session?.token else null
    }

    private fun publish(stored: StoredIdentity?) {
        _state.update {
            it.copy(
                loading = false,
                code = stored?.identity?.code,
                displayName = stored?.displayName.orEmpty(),
                avatar = stored?.avatar,
                registered = stored?.registered == true,
                backedUp = stored?.backedUp == true,
            )
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocialException) {
                fail(e.message ?: "Something went wrong.")
            } catch (e: Exception) {
                fail("Something went wrong. Try again.")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun cleanName(input: String): String? {
        val name = input.replace(Regex("""\s+"""), " ").trim()
        val length = name.codePointCount(0, name.length)
        return name.takeIf { length in 2..24 && name.none { ch -> ch.isISOControl() } }
    }
}
