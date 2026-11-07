package com.moodify.app.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Works out which country's music catalogue to show when the user has not
 * picked one.
 *
 * The phone's language setting is a poor signal on its own: many phones in
 * India (and elsewhere) run "English (United States)", which would serve the
 * US catalogue. The mobile network and SIM say where the user actually is, so
 * they win; the language setting and finally "US" are fallbacks.
 */
object RegionDetector {
    fun detect(context: Context): String {
        val telephony = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()
        return pick(
            networkCountry = runCatching { telephony?.networkCountryIso }.getOrNull(),
            simCountry = runCatching { telephony?.simCountryIso }.getOrNull(),
            localeCountry = Locale.getDefault().country,
        )
    }

    /** First usable two-letter country code from the most to the least reliable source. */
    internal fun pick(networkCountry: String?, simCountry: String?, localeCountry: String?): String =
        listOf(networkCountry, simCountry, localeCountry)
            .firstNotNullOfOrNull { code -> code?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) } }
            ?: "US"
}
