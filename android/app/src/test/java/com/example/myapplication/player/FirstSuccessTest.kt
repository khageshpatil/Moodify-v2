package com.moodify.app.player

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FirstSuccessTest {
    /** How each fake client behaves: answer or fail after a delay, or never answer. */
    private sealed interface Client
    private data class Answers(val afterMs: Long) : Client
    private data class Fails(val afterMs: Long) : Client
    private data object Hangs : Client

    private val started = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()

    private suspend fun run(clients: Map<String, Client>, scope: kotlinx.coroutines.CoroutineScope): String =
        firstSuccess(clients.keys.toList(), hedgeAfterMs = 1_500, perTryMs = 4_000, scope = scope) { name ->
            started += name
            try {
                when (val client = clients.getValue(name)) {
                    is Answers -> { delay(client.afterMs); name }
                    is Fails -> { delay(client.afterMs); throw IOException("$name failed") }
                    Hangs -> awaitCancellation()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += name
                throw e
            }
        }

    @Test
    fun aFastFirstClientIsTheOnlyOneAsked() = runTest {
        assertEquals("A", run(linkedMapOf("A" to Answers(400), "B" to Answers(100)), backgroundScope))
        assertEquals(listOf("A"), started)
        assertEquals(400, currentTime)
    }

    @Test
    fun aSlowClientGetsCompanyAndTheQuickerAnswerWins() = runTest {
        assertEquals("B", run(linkedMapOf("A" to Answers(3_000), "B" to Answers(500)), backgroundScope))
        assertEquals(listOf("A", "B"), started)
        assertEquals(2_000, currentTime) // B started at the 1.5 s hedge and answered 0.5 s later
        testScheduler.runCurrent()
        assertEquals(listOf("A"), cancelled) // the slow one is stopped, not waited for
    }

    @Test
    fun aFailureStartsTheNextClientStraightAway() = runTest {
        assertEquals("B", run(linkedMapOf("A" to Fails(200), "B" to Answers(300)), backgroundScope))
        assertEquals(500, currentTime)
    }

    @Test
    fun aHungClientIsGivenUpOnAfterItsOwnTime() = runTest {
        val result = run(linkedMapOf("A" to Hangs, "B" to Hangs, "C" to Answers(100)), backgroundScope)
        assertEquals("C", result)
        // A at 0, B alongside at 1.5 s; A runs out at 4 s and C starts then.
        assertEquals(4_100, currentTime)
        assertEquals(listOf("A", "B", "C"), started)
    }

    @Test
    fun neverMoreThanTwoAtOnce() = runTest {
        var running = 0
        var most = 0
        firstSuccess((1..6).toList(), hedgeAfterMs = 100, perTryMs = 1_000, scope = backgroundScope) { n ->
            running++; most = maxOf(most, running)
            try { if (n < 6) awaitCancellation() else "done" } finally { running-- }
        }
        assertEquals(2, most)
    }

    @Test
    fun aCancellationThrownInsideATryIsJustAFailure() = runTest {
        val result = firstSuccess(listOf(1, 2), hedgeAfterMs = 1_500, perTryMs = 4_000, scope = backgroundScope) { n ->
            if (n == 1) throw kotlinx.coroutines.CancellationException("a library gave up") else "second"
        }
        assertEquals("second", result)
        assertEquals(0, currentTime) // no waiting for the hedge or a timeout
    }

    @Test
    fun theLastFailureIsReportedWhenAllFail() = runTest {
        try {
            run(linkedMapOf("A" to Fails(10), "B" to Fails(20)), backgroundScope)
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals("B failed", e.message)
        }
    }

    @Test
    fun theCallerGivingUpStopsEveryTry() = runTest {
        val outcome = kotlinx.coroutines.withTimeoutOrNull(2_000) {
            run(linkedMapOf("A" to Hangs, "B" to Hangs), backgroundScope)
        }
        assertEquals(null, outcome)
        testScheduler.runCurrent()
        assertTrue(cancelled.containsAll(listOf("A", "B")))
    }
}
