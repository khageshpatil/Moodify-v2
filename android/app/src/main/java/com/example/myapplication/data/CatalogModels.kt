package com.moodify.app.data

data class BrowseEndpoint(
    val browseId: String? = null,
    val videoId: String? = null,
    val params: String? = null,
    val pageType: String? = null,
)

data class CatalogItem(
    val type: String,
    val id: String,
    val title: String,
    val artworkUrl: String = "",
    val track: Track? = null,
    val endpoint: BrowseEndpoint? = null,
    val accentColor: Long? = null,
)

data class CatalogSection(
    val id: String,
    val title: String,
    val layout: String = "carousel",
    val items: List<CatalogItem> = emptyList(),
    val endpoint: BrowseEndpoint? = null
)

data class CatalogPage(
    val version: Int = 1,
    val surface: String,
    val title: String,
    val sections: List<CatalogSection> = emptyList(),
    val actions: List<CatalogItem> = emptyList(),
    val nextCursor: String? = null,
    /**
     * YouTube Music's own filter chips (Relax, Feel good, Podcasts…) on Home.
     * Nullable because pages cached before chips existed deserialize it as null.
     */
    val chips: List<CatalogChip>? = null,
    /** YouTube session that served this page; its paging token only works with it. */
    val visitorData: String? = null,
)

/** A Home filter chip; browsing FEmusic_home with [params] reloads Home for it. */
data class CatalogChip(val title: String, val params: String, val selected: Boolean = false)

data class SearchSuggestions(
    val queries: List<String> = emptyList(),
)

/** Merge an InnerTube continuation into the page shelf it extends. */
internal fun mergeCatalogPages(current: CatalogPage, next: CatalogPage): CatalogPage {
    val continuationSectionId = "${current.surface}-items"
    val isContinuationShelf = next.sections.size == 1 &&
        next.sections.single().id == continuationSectionId &&
        current.sections.isNotEmpty()

    val mergedSections = if (isContinuationShelf) {
        val continuationItems = next.sections.single().items
        current.sections.mapIndexed { index, section ->
            if (index == 0) {
                section.copy(
                    items = (section.items + continuationItems)
                        .distinctBy { "${it.type}:${it.id}" },
                )
            } else {
                section
            }
        }
    } else {
        (current.sections + next.sections)
            .groupBy { it.id }
            .values
            .map { sections ->
                val first = sections.first()
                first.copy(
                    items = sections.flatMap { it.items }
                        .distinctBy { "${it.type}:${it.id}" },
                )
            }
    }

    return current.copy(
        sections = mergedSections,
        actions = (current.actions + next.actions).distinctBy { it.id },
        nextCursor = next.nextCursor,
    )
}
