package com.moodify.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeCatalogParserTest {
    @Test
    fun parsesSearchSuggestionsAndRemovesDuplicates() {
        val root = JSONObject(
            """
            {
              "contents": [
                {"searchSuggestionRenderer": {
                  "suggestion": {"runs":[{"text":"never gonna give you up"}]}
                }},
                {"searchSuggestionRenderer": {
                  "navigationEndpoint":{"searchEndpoint":{"query":"never gonna change"}}
                }},
                {"searchSuggestionRenderer": {
                  "suggestion": {"simpleText":"never gonna give you up"}
                }}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("never gonna give you up", "never gonna change"),
            CatalogRendererParser.parseSuggestions(root),
        )
    }

    @Test
    fun parsesTrackShelfAndContinuation() {
        val root = JSONObject(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [{
                    "musicShelfRenderer": {
                      "title": {"runs":[{"text":"Recommended"}]},
                      "contents": [{
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": {"videoId":"dQw4w9WgXcQ"},
                          "flexColumns": [
                            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Never Gonna Give You Up"}]}}},
                            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Rick Astley"}]}}}
                          ],
                          "fixedColumns": [{"musicResponsiveListItemFixedColumnRenderer":{"text":{"simpleText":"3:33"}}}],
                          "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}]}
                        }
                      }]
                    }
                  }],
                  "continuations": [{"nextContinuationData":{"continuation":"next-token"}}]
                }
              }
            }
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root = root,
            surface = "home",
            fallbackTitle = "Home",
            locale = CatalogLocale("en", "US"),
        )

        assertEquals("home", page.surface)
        assertEquals("next-token", page.nextCursor)
        assertEquals(1, page.sections.size)
        val track = page.sections.single().items.single().track
        assertNotNull(track)
        assertEquals("dQw4w9WgXcQ", track?.id)
        assertEquals("Never Gonna Give You Up", track?.title)
        assertEquals("Rick Astley", track?.artist)
        assertEquals(213, track?.durationSeconds)
        assertTrue(track?.albumArtUrl?.contains("hqdefault") == true)
    }

    @Test
    fun readsCarouselShelfTitleFromNestedMusicHeaderRenderer() {
        val root = JSONObject(
            """
            {
              "contents":{"sectionListRenderer":{"contents":[
                {"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"Made for you"}]}
                  }},
                  "contents":[{"musicTwoRowItemRenderer":{
                    "title":{"simpleText":"Daily Mix"},
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL123"}}
                  }}]
                }}
              ]}}
            }
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root = root,
            surface = "home",
            fallbackTitle = "Home",
            locale = CatalogLocale("en", "US"),
        )

        assertEquals("Made for you", page.sections.single().title)
        assertEquals("Daily Mix", page.sections.single().items.single().title)
    }

    @Test
    fun parsesBrowseEntityWithoutTrack() {
        val root = JSONObject(
            """
            {"items":[{"musicTwoRowItemRenderer":{
              "title":{"simpleText":"Discovery Mix"},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL123"}},
              "thumbnail":{"thumbnails":[{"url":"https://example.test/art.jpg"}]}
            }}]}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(root, "explore", "Explore", CatalogLocale("en", "IN"))
        val item = page.sections.single().items.single()
        assertEquals("playlist", item.type)
        assertEquals("VLPL123", item.id)
        assertEquals("Discovery Mix", item.title)
        assertEquals("VLPL123", item.endpoint?.browseId)
    }

    @Test
    fun parsesNestedMusicThumbnailArtwork() {
        val root = JSONObject(
            """
            {"items":[{"musicTwoRowItemRenderer":{
              "title":{"simpleText":"Nested artwork"},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}},
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"https://lh3.googleusercontent.com/art=w60-h60"},
                {"url":"https://lh3.googleusercontent.com/art=w544-h544"}
              ]}}}
            }}]}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(root, "search", "Search", CatalogLocale("en", "US"))
        assertEquals(
            "https://lh3.googleusercontent.com/art=w544-h544",
            page.sections.single().items.single().track?.albumArtUrl,
        )
    }

    @Test
    fun parsesBrowseThumbnailRendererArtwork() {
        val root = JSONObject(
            """
            {"items":[{"musicTwoRowItemRenderer":{
              "title":{"simpleText":"25"},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_PiyfuVl6aYd"}},
              "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"https://yt3.googleusercontent.com/art=w226-h226"},
                {"url":"https://yt3.googleusercontent.com/art=w544-h544"}
              ]}}}
            }}]}
            """.trimIndent(),
        )

        val item = CatalogRendererParser
            .parse(root, "browse", "Browse", CatalogLocale("en", "US"))
            .sections.single().items.single()

        assertEquals("album", item.type)
        assertEquals("MPREb_PiyfuVl6aYd", item.endpoint?.browseId)
        assertEquals(
            "https://yt3.googleusercontent.com/art=w544-h544",
            item.artworkUrl,
        )
    }

    @Test
    fun classifiesMusicArtistByBrowsePageType() {
        val root = JSONObject(
            """
            {"items":[{"musicTwoRowItemRenderer":{
              "title":{"simpleText":"Adele"},
              "navigationEndpoint":{"browseEndpoint":{
                "browseId":"UCsRM0YB_daba4H6j9LQxq5Q",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
                  "pageType":"MUSIC_PAGE_TYPE_ARTIST"
                }}
              }},
              "thumbnail":{"thumbnails":[{"url":"https://example.test/adele.jpg"}]}
            }}]}
            """.trimIndent(),
        )

        val item = CatalogRendererParser
            .parse(root, "explore", "Explore", CatalogLocale("en", "IN"))
            .sections.single().items.single()

        assertEquals("artist", item.type)
        assertEquals("MUSIC_PAGE_TYPE_ARTIST", item.endpoint?.pageType)
    }

    @Test
    fun readsTitleFromResponsiveMusicHeader() {
        val root = JSONObject(
            """
            {"header":{"musicResponsiveHeaderRenderer":{
              "title":{"runs":[{"text":"Adele"}]}
            }}}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "browse",
            "Browse",
            CatalogLocale("en", "IN"),
        )

        assertEquals("Adele", page.title)
    }

    @Test
    fun readsTitleFromImmersiveArtistHeader() {
        val root = JSONObject(
            """
            {"header":{"musicImmersiveHeaderRenderer":{
              "title":{"runs":[{"text":"Adele"}]},
              "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                {"url":"https://example.test/adele-banner.jpg"}
              ]}}}
            }}}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "browse",
            "Browse",
            CatalogLocale("en", "IN"),
        )

        assertEquals("Adele", page.title)
    }

    @Test
    fun parsesMusicShelfContinuationRowsAndNextCursor() {
        val root = JSONObject(
            """
            {"continuationContents":{"musicShelfContinuation":{
              "contents":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"continuation-track"},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Next Song"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Next Artist"}]}}}
                ]
              }}],
              "continuations":[{"nextContinuationData":{"continuation":"next-page-token"}}]
            }}}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "playlist",
            "Lofi House",
            CatalogLocale("en", "IN"),
        )

        assertEquals("next-page-token", page.nextCursor)
        assertEquals("playlist-items", page.sections.single().id)
        assertEquals("Lofi House", page.sections.single().title)
        assertEquals("continuation-track", page.sections.single().items.single().track?.id)
    }

    @Test
    fun parsesMusicPlaylistShelfContinuationRowsAndNextCursor() {
        val root = JSONObject(
            """
            {"continuationContents":{"musicPlaylistShelfContinuation":{
              "contents":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"playlist-continuation-track"},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Next Mix Song"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Next Mix Artist"}]}}}
                ]
              }}],
              "continuations":[{"nextContinuationData":{"continuation":"playlist-next-token"}}]
            }}}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "playlist",
            "Lofi House Mix",
            CatalogLocale("en", "IN"),
        )

        assertEquals("playlist-next-token", page.nextCursor)
        assertEquals("playlist-items", page.sections.single().id)
        assertEquals("Lofi House Mix", page.sections.single().title)
        assertEquals(
            "playlist-continuation-track",
            page.sections.single().items.single().track?.id,
        )
    }

    @Test
    fun playlistCursorComesFromTrackShelfInsteadOfRelatedSection() {
        val root = JSONObject(
            """
            {
              "contents":{"twoColumnBrowseResultsRenderer":{
                "secondaryContents":{"sectionListRenderer":{
                  "continuations":[{"nextContinuationData":{"continuation":"related-playlists-next"}}],
                  "contents":[{"musicPlaylistShelfRenderer":{
                    "contents":[
                      {"musicResponsiveListItemRenderer":{
                        "playlistItemData":{"videoId":"playlist-track"},
                        "flexColumns":[
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Track"}}},
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Artist"}}}
                        ]
                      }},
                      {"continuationItemRenderer":{
                        "continuationEndpoint":{"continuationCommand":{"token":"playlist-tracks-next"}}
                      }}
                    ]
                  }}]
                }}
              }}
            }
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "playlist",
            "Playlist",
            CatalogLocale("en", "IN"),
        )

        assertEquals("playlist-tracks-next", page.nextCursor)
        assertEquals("playlist-track", page.sections.single().items.single().track?.id)
    }

    @Test
    fun artistCursorComesFromPrimarySongShelfInsteadOfRelatedSection() {
        val root = JSONObject(
            """
            {
              "contents":{"twoColumnBrowseResultsRenderer":{
                "primaryContents":{"sectionListRenderer":{"contents":[
                  {"musicShelfRenderer":{
                    "title":{"simpleText":"Songs"},
                    "contents":[
                      {"musicResponsiveListItemRenderer":{
                        "playlistItemData":{"videoId":"artist-song"},
                        "flexColumns":[
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Artist Song"}}},
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Artist"}}}
                        ]
                      }},
                      {"continuationItemRenderer":{
                        "continuationEndpoint":{"continuationCommand":{"token":"artist-songs-next"}}
                      }}
                    ]
                  }}
                ]}},
                "secondaryContents":{"sectionListRenderer":{
                  "continuations":[{"nextContinuationData":{"continuation":"related-artists-next"}}],
                  "contents":[{"musicCarouselShelfRenderer":{
                    "contents":[{"musicTwoRowItemRenderer":{
                      "title":{"simpleText":"Related playlist"},
                      "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRELATED"}}
                    }}]
                  }}]
                }}
              }}
            }
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "artist",
            "Artist",
            CatalogLocale("en", "IN"),
        )

        assertEquals("artist-songs-next", page.nextCursor)
        assertTrue(
            page.sections.flatMap { it.items }.any { it.track?.id == "artist-song" },
        )
    }

    @Test
    fun artistCursorReadsSelectedSingleColumnTab() {
        val root = JSONObject(
            """
            {
              "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{
                "tabRenderer":{"selected":true,"content":{"sectionListRenderer":{"contents":[
                  {"musicShelfRenderer":{
                    "title":{"simpleText":"Top songs"},
                    "contents":[
                      {"musicResponsiveListItemRenderer":{
                        "playlistItemData":{"videoId":"single-column-song"},
                        "flexColumns":[
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Song"}}},
                          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Artist"}}}
                        ]
                      }},
                      {"continuationItemRenderer":{
                        "continuationEndpoint":{"continuationCommand":{"token":"single-column-next"}}
                      }}
                    ]
                  }}
                ]}}}
              }]}}
            }
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "artist",
            "Artist",
            CatalogLocale("en", "IN"),
        )

        assertEquals("single-column-next", page.nextCursor)
        assertTrue(
            page.sections.flatMap { it.items }.any { it.track?.id == "single-column-song" },
        )
    }

    @Test
    fun parsesTrackRowsAppendedByContinuationAction() {
        val root = JSONObject(
            """
            {"onResponseReceivedActions":[{"appendContinuationItemsAction":{
              "continuationItems":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"appended-track"},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Appended Song"}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Appended Artist"}}}
                ]
              }}]
            }}]}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(
            root,
            "playlist",
            "Playlist",
            CatalogLocale("en", "IN"),
        )

        assertEquals("appended-track", page.sections.single().items.single().track?.id)
    }

    @Test
    fun mergesContinuationRowsIntoTheExistingShelfAndDeduplicates() {
        fun item(id: String) = CatalogItem(
            type = "track",
            id = id,
            title = id,
            track = Track(id = id, title = id, artist = "Artist"),
        )
        val current = CatalogPage(
            surface = "search",
            title = "lofi playlist",
            sections = listOf(
                CatalogSection("featured-playlists", "Featured playlists", items = listOf(item("one"))),
            ),
            nextCursor = "cursor-1",
        )
        val next = CatalogPage(
            surface = "search",
            title = "lofi playlist",
            sections = listOf(
                CatalogSection("search-items", "lofi playlist", items = listOf(item("one"), item("two"))),
            ),
            nextCursor = "cursor-2",
        )

        val merged = mergeCatalogPages(current, next)

        assertEquals(1, merged.sections.size)
        assertEquals(listOf("one", "two"), merged.sections.single().items.map { it.id })
        assertEquals("cursor-2", merged.nextCursor)
    }

    @Test
    fun appliesBrowseHeaderArtworkToTrackRowsWithoutTheirOwnThumbnail() {
        val root = JSONObject(
            """
            {
              "header":{"musicResponsiveHeaderRenderer":{
                "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
                  {"url":"https://yt3.googleusercontent.com/album=w544-h544"}
                ]}}}
              }},
              "items":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"dQw4w9WgXcQ"},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Adele"}]}}}
                ]
              }}]
            }
            """.trimIndent(),
        )

        val track = CatalogRendererParser
            .parse(root, "browse", "Browse", CatalogLocale("en", "US"))
            .sections.single().items.single().track

        assertEquals("https://yt3.googleusercontent.com/album=w544-h544", track?.albumArtUrl)
    }

    @Test
    fun mapsCurrentMusicSearchMetadataFromAccessibilityLabel() {
        val root = JSONObject(
            """
            {"items":[{"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"9IzKueQ2ZxY"},
              "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{
                "musicPlayButtonRenderer":{"accessibilityPlayData":{"accessibilityData":{
                  "label":"Play Make You Feel My Love - Adele"
                }}}
              }}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Make You Feel My Love"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":" • "},{"text":"3:33"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"157M plays"}]}}}
              ]
            }}]}
            """.trimIndent(),
        )

        val track = CatalogRendererParser
            .parse(root, "search", "Search", CatalogLocale("en", "US"))
            .sections.single().items.single().track

        assertEquals("Adele", track?.artist)
        assertEquals("", track?.album)
        assertEquals(213, track?.durationSeconds)
    }

    @Test
    fun parsesMoodsAndGenresGridButtonsWithBrowseParametersAndAccentColors() {
        val root = JSONObject(
            """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
              "sectionListRenderer":{"contents":[
                {"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"Moods & moments"}]}}},"items":[
                  {"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Chill"}]},"solid":{"leftStripeColor":4288988671},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX0JOQWZFeDByc2Jm"}}}},
                  {"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Commute"}]},"solid":{"leftStripeColor":4294951424},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX044Z2o5WERLckpU"}}}}
                ]}},
                {"gridRenderer":{"header":{"gridHeaderRenderer":{"title":{"runs":[{"text":"Genres"}]}}},"items":[
                  {"musicNavigationButtonRenderer":{"buttonText":{"runs":[{"text":"Bengali"}]},"solid":{"leftStripeColor":4294961024},"clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"ggMPOg1uX1ZEZzBvYUp0TGNI"}}}}
                ]}}
              ]}
            }}}]}}}}
            """.trimIndent(),
        )

        val page = CatalogRendererParser.parse(root, "moods", "Moods & genres", CatalogLocale("en", "US"))

        assertEquals(listOf("Moods & moments", "Genres"), page.sections.map { it.title })
        assertTrue(page.sections.all { it.layout == "mood-grid" })
        val chill = page.sections.first().items.first()
        assertEquals("navigation", chill.type)
        assertEquals("Chill", chill.title)
        assertEquals("FEmusic_moods_and_genres_category", chill.endpoint?.browseId)
        assertEquals("ggMPOg1uX0JOQWZFeDByc2Jm", chill.endpoint?.params)
        assertEquals(4288988671L, chill.accentColor)
    }

    @Test
    fun parsesExploreMoodCarouselButtonsAsNavigationTiles() {
        val root = JSONObject(
            """
            {"contents":{"sectionListRenderer":{"contents":[{"musicCarouselShelfRenderer":{
              "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Moods & genres"}]}}},
              "contents":[{"musicNavigationButtonRenderer":{
                "buttonText":{"runs":[{"text":"Chill"}]},
                "solid":{"leftStripeColor":4288988671},
                "clickCommand":{"browseEndpoint":{"browseId":"FEmusic_moods_and_genres_category","params":"mood-param"}}
              }}]
            }}]}}}
            """.trimIndent(),
        )

        val section = CatalogRendererParser
            .parse(root, "explore", "Explore", CatalogLocale("en", "US"))
            .sections.single()

        assertEquals("mood-carousel", section.layout)
        assertEquals("Chill", section.items.single().title)
        assertEquals("mood-param", section.items.single().endpoint?.params)
    }
}
