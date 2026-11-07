package com.moodify.app.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small Room-backed page cache for native InnerTube catalog responses.
 *
 * The cache is intentionally keyed by request plus locale. A page fetched in
 * one country is never silently presented as another country's home/charts
 * result. The repository may still return a stale page when the network is
 * unavailable, but only for the same locale and request.
 */
class CatalogCacheStore(
    context: android.content.Context,
    private val dao: MoodifyDao = MoodifyDatabase.get(context.applicationContext).moodifyDao(),
) {
    private val gson = Gson()

    suspend fun read(key: String, locale: CatalogLocale): CatalogPage? = withContext(Dispatchers.IO) {
        val entry = dao.catalogPage(key) ?: return@withContext null
        if (entry.localeLanguage != locale.language || entry.localeRegion != locale.region) {
            // Never prune here: an early read can carry the default locale before
            // stored settings arrive, and must not wipe the user's real cache.
            return@withContext null
        }
        runCatching { gson.fromJson(entry.pageJson, CatalogPage::class.java) }.getOrNull()
    }

    suspend fun write(key: String, locale: CatalogLocale, page: CatalogPage) = withContext(Dispatchers.IO) {
        dao.upsertCatalogPage(
            CatalogPageCacheEntity(
                cacheKey = key,
                localeLanguage = locale.language,
                localeRegion = locale.region,
                pageJson = gson.toJson(page),
                savedAtMs = System.currentTimeMillis(),
            ),
        )
        // Keep catalog cache bounded without touching user library/history.
        dao.pruneCatalogPages(System.currentTimeMillis() - CACHE_RETENTION_MS)
    }

    private companion object {
        const val CACHE_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
