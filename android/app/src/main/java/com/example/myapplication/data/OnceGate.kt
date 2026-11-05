package com.moodify.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs a suspend block at most once successfully, no matter how many callers
 * race to it. Used for one-time legacy imports so they happen lazily on a
 * background dispatcher instead of blocking the main thread in a constructor.
 * A failed run is retried by the next caller.
 */
class OnceGate {
    private val mutex = Mutex()
    @Volatile private var done = false

    suspend fun run(block: suspend () -> Unit) {
        if (done) return
        mutex.withLock {
            if (done) return
            block()
            done = true
        }
    }
}
