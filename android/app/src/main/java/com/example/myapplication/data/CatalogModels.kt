package com.example.myapplication.data

data class BrowseEndpoint(
    val browseId: String? = null,
    val videoId: String? = null,
    val params: String? = null
)

data class CatalogItem(
    val type: String,
    val id: String,
    val title: String,
    val artworkUrl: String = "",
    val track: Track? = null,
    val endpoint: BrowseEndpoint? = null
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
    val nextCursor: String? = null
)
