package com.moodify.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CatalogCacheOfflineTest {
    @Test
    fun catalogUsesSameLocaleCacheOfflineAndRejectsAnotherRegionsPage() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MoodifyDatabase::class.java).build()

        try {
            val us = CatalogLocale("en", "US")
            val india = CatalogLocale("en", "IN")
            val cache = CatalogCacheStore(context, database.moodifyDao())
            val cachedUsPage = pageFor("United States")
            cache.write("home", us, cachedUsPage)

            val sameRegionRepository = offlineRepository(cache, us)
            val stalePage = sameRegionRepository.getHomeCatalog()
            assertEquals("Home for United States", stalePage.title)
            assertEquals("us-track", stalePage.sections.single().items.single().id)

            val otherRegionRepository = offlineRepository(cache, india)
            val failure = runCatching { otherRegionRepository.getHomeCatalog() }.exceptionOrNull()
            assertTrue("A US cache page must not leak into the India locale", failure is IOException)
        } finally {
            database.close()
        }
    }

    private fun offlineRepository(cache: CatalogCacheStore, locale: CatalogLocale) = MusicRepository(
        catalogClient = InnerTubeCatalogClient(
            httpClient = OkHttpClient.Builder()
                .addInterceptor { throw IOException("synthetic offline catalog") }
                .build(),
            localeProvider = { locale },
        ),
        catalogCache = cache,
        localeProvider = { locale },
    )

    private fun pageFor(region: String) = CatalogPage(
        surface = "home",
        title = "Home for $region",
        sections = listOf(
            CatalogSection(
                id = "home-tracks",
                title = "Regional picks",
                items = listOf(CatalogItem(type = "track", id = "us-track", title = region)),
            ),
        ),
    )
}
