package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionDetectorTest {
    @Test
    fun networkCountryBeatsAnEnglishUsPhoneSetting() {
        // An "English (United States)" phone on an Indian network should get India's catalogue.
        assertEquals("IN", RegionDetector.pick(networkCountry = "in", simCountry = "in", localeCountry = "US"))
    }

    @Test
    fun fallsBackToSimThenPhoneLanguageThenUs() {
        assertEquals("IN", RegionDetector.pick(networkCountry = "", simCountry = "in", localeCountry = "US"))
        assertEquals("GB", RegionDetector.pick(networkCountry = null, simCountry = null, localeCountry = "GB"))
        assertEquals("US", RegionDetector.pick(networkCountry = null, simCountry = "", localeCountry = ""))
    }

    @Test
    fun ignoresMalformedCodes() {
        assertEquals("IN", RegionDetector.pick(networkCountry = "310", simCountry = "in", localeCountry = "US"))
    }
}
