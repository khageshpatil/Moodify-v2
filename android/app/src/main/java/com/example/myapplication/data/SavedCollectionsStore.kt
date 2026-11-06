package com.moodify.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Albums, YouTube playlists and artists the listener saved to their Library. */
class SavedCollectionsStore(
    context: Context,
    database: MoodifyDatabase = MoodifyDatabase.get(context.applicationContext),
) {
    private val dao = database.moodifyDao()

    /** Newest first, as catalog items so every card and row can show them. */
    fun observe(): Flow<List<CatalogItem>> = dao.observeSavedCollections().map { rows -> rows.map { it.toItem() } }

    suspend fun save(item: CatalogItem, subtitle: String = "") {
        val endpoint = item.endpoint
        dao.upsertSavedCollection(
            SavedCollectionEntity(
                id = item.id,
                type = item.type.lowercase(),
                title = item.title,
                subtitle = subtitle,
                artworkUrl = item.artworkUrl,
                params = endpoint?.params,
                pageType = endpoint?.pageType,
                savedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun remove(id: String) = dao.deleteSavedCollection(id)

    private fun SavedCollectionEntity.toItem() = CatalogItem(
        type = type,
        id = id,
        title = title,
        artworkUrl = artworkUrl,
        endpoint = BrowseEndpoint(browseId = id, params = params, pageType = pageType),
    )
}
