package com.moodify.app.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the most recent crash on this device only (Settings › Advanced), so a
 * problem can be shared by hand. Nothing is ever sent anywhere automatically.
 */
object CrashLog {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 16_000

    fun install(context: Context, versionName: String) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, versionName, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash report, or null when the app hasn't crashed (or it was cleared). */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }

    internal fun format(versionName: String, threadName: String, error: Throwable, atMs: Long): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMs))
        return buildString {
            appendLine("Moodify $versionName crashed at $time")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MODEL}")
            appendLine("Thread: $threadName")
            appendLine()
            append(trace)
        }.take(MAX_CHARS)
    }

    private fun write(context: Context, versionName: String, thread: Thread, error: Throwable) {
        file(context).writeText(format(versionName, thread.name, error, System.currentTimeMillis()))
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
