package com.moodify.app.data

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun reportNamesVersionThreadAndCause() {
        val report = CrashLog.format("1.1.0", "main", IllegalStateException("boom"), atMs = 0L)

        assertTrue(report.startsWith("Moodify 1.1.0 crashed at "))
        assertTrue(report.contains("Thread: main"))
        assertTrue(report.contains("IllegalStateException: boom"))
    }

    @Test
    fun hugeTracesAreCapped() {
        var error: Throwable = RuntimeException("root")
        repeat(400) { error = RuntimeException("wrap $it", error) }

        assertTrue(CrashLog.format("1.1.0", "main", error, atMs = 0L).length <= 16_000)
    }
}
