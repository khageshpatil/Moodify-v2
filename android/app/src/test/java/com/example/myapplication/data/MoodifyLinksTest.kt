package com.moodify.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodifyLinksTest {
    private val code = "MOOD-7K2P-QX9M"

    @Test
    fun sharedLinksAreWebLinks() {
        assertEquals("https://moodify-v2-two.vercel.app/add/$code", MoodifyLinks.addFriend(code))
        assertEquals("https://moodify-v2-two.vercel.app/room/K7P2QX", MoodifyLinks.room("K7P2QX"))
    }

    @Test
    fun webAndAppLinksMeanTheSameThing() {
        assertEquals(MoodifyLink.AddFriend(code), MoodifyLinks.parse(MoodifyLinks.addFriend(code)))
        assertEquals(MoodifyLink.AddFriend(code), MoodifyLinks.parse("moodify://add/$code"))
        assertEquals(MoodifyLink.Room("K7P2QX"), MoodifyLinks.parse(MoodifyLinks.room("K7P2QX")))
        assertEquals(MoodifyLink.Room("K7P2QX"), MoodifyLinks.parse("moodify://room/K7P2QX"))
    }

    @Test
    fun toleratesWhatMessagingAppsDoToLinks() {
        assertEquals(MoodifyLink.AddFriend(code), MoodifyLinks.parse("https://Moodify-v2-two.vercel.app/add/$code/?utm_source=whatsapp"))
        assertEquals(MoodifyLink.Room("K7P2QX"), MoodifyLinks.parse("  https://moodify-v2-two.vercel.app/room/K7P2QX#top "))
    }

    @Test
    fun appOnlyLinks() {
        assertEquals(MoodifyLink.Chat(code), MoodifyLinks.parse("moodify://chat/$code"))
        assertEquals(MoodifyLink.LinkDevice("ABCD2345", "k3y_-x"), MoodifyLinks.parse("moodify://link/ABCD2345?k=k3y_-x"))
        assertNull(MoodifyLinks.parse("https://moodify-v2-two.vercel.app/chat/$code"))
        assertNull(MoodifyLinks.parse("https://moodify-v2-two.vercel.app/link/ABCD2345?k=x"))
    }

    @Test
    fun ignoresEverythingElse() {
        assertNull(MoodifyLinks.parse("https://moodify-v2-two.vercel.app/privacy"))
        assertNull(MoodifyLinks.parse("https://moodify-v2-two.vercel.app/add/"))
        assertNull(MoodifyLinks.parse("http://moodify-v2-two.vercel.app/add/$code"))
        assertNull(MoodifyLinks.parse("https://evil.example/add/$code"))
        assertNull(MoodifyLinks.parse("https://music.youtube.com/watch?v=BddP6PYo2gs"))
        assertNull(MoodifyLinks.parse(null))
        assertNull(MoodifyLinks.parse("not a link"))
    }

    @Test
    fun findsTheFriendCodeInScannedOrPastedText() {
        assertEquals(code, MoodifyLinks.friendCodeIn("https://moodify-v2-two.vercel.app/add/$code"))
        assertEquals(code, MoodifyLinks.friendCodeIn("Add me on Moodify: $code\nmoodify://add/$code"))
        assertNull(MoodifyLinks.friendCodeIn("https://example.com/add/$code"))
        assertNull(MoodifyLinks.friendCodeIn(code))
    }
}
