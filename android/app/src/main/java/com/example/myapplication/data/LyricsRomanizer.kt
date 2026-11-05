package com.moodify.app.data

import android.os.Build
import android.icu.text.Transliterator
import androidx.annotation.RequiresApi

/** Uses Android's on-device ICU rules to provide a readable Latin fallback for non-Latin lyric scripts. */
object LyricsRomanizer {
    private val transliterator = ThreadLocal<Any?>()

    fun romanize(text: String): String? {
        if (text.isBlank() || !text.any(::isNonLatinScript)) return null
        // Android's bundled ICU transliterator was added in API 29. Provider
        // romanization remains usable on API 24–28; skip only the local fallback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return romanizeWithAndroidIcu(text)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun romanizeWithAndroidIcu(text: String): String? {
        val engine = (transliterator.get() as? Transliterator)
            ?: Transliterator.getInstance("Any-Latin; Latin-ASCII").also(transliterator::set)
        val result = engine.transliterate(text)
            .replace(Regex("\\s+"), " ")
            .trim()
        return result.takeIf { it.isNotBlank() && !it.equals(text.trim(), ignoreCase = true) }
    }

    private fun isNonLatinScript(char: Char): Boolean {
        val script = Character.UnicodeScript.of(char.code)
        return script != Character.UnicodeScript.LATIN &&
            script != Character.UnicodeScript.COMMON &&
            script != Character.UnicodeScript.INHERITED
    }
}
