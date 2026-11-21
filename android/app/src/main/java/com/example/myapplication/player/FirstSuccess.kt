package com.moodify.app.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tries [candidates] in order and returns the first success.
 *
 * - Each try gets [perTryMs].
 * - When the running try hasn't answered within [hedgeAfterMs], the next one starts
 *   alongside it, never more than [maxParallel] at once. A slow client then costs
 *   [hedgeAfterMs], not its whole timeout.
 * - A failure starts the next candidate straight away.
 * - Tries run in [scope], so one stuck in a blocking call can be abandoned: when a try
 *   succeeds (or the caller gives up) the others are cancelled, not waited for.
 *
 * Throws the last failure when every candidate fails.
 */
internal suspend fun <C, R> firstSuccess(
    candidates: List<C>,
    hedgeAfterMs: Long,
    perTryMs: Long,
    scope: CoroutineScope,
    maxParallel: Int = 2,
    onFailure: (C, Throwable) -> Unit = { _, _ -> },
    attempt: suspend (C) -> R,
): R {
    if (candidates.isEmpty()) throw IllegalArgumentException("Nothing to try")
    val outcomes = Channel<Pair<C, Result<R>>>(Channel.UNLIMITED)
    val running = mutableListOf<Job>()
    var next = 0
    var active = 0
    var lastFailure: Throwable? = null

    fun startNext() {
        val candidate = candidates.getOrNull(next++) ?: return
        active++
        running += scope.launch {
            val outcome = try {
                Result.success(withTimeout(perTryMs) { attempt(candidate) })
            } catch (timedOut: TimeoutCancellationException) {
                Result.failure(timedOut) // this try's own time ran out
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled // abandoned by the caller
                Result.failure(cancelled) // thrown from inside the try: just another failure
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            outcomes.trySend(candidate to outcome)
        }
    }

    try {
        startNext()
        while (active > 0) {
            val canHedge = active < maxParallel && next < candidates.size
            val received = if (canHedge) withTimeoutOrNull(hedgeAfterMs) { outcomes.receive() } else outcomes.receive()
            if (received == null) {
                startNext() // the running try is slow: start the next one alongside it
                continue
            }
            active--
            val (candidate, outcome) = received
            outcome.onSuccess { return it }
            val failure = outcome.exceptionOrNull()!!
            lastFailure = failure
            onFailure(candidate, failure)
            if (active < maxParallel) startNext()
        }
        throw lastFailure ?: IllegalStateException("Nothing succeeded")
    } finally {
        running.forEach { it.cancel() }
        outcomes.close()
    }
}
