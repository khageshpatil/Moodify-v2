# Moodify InnerTube capability matrix

Audit basis: repository code and the installed `server/node_modules/youtubei.js` version `18.0.0`. “Available” means the library exposes a concrete method or the repository already exercises the mechanism. It does not mean the endpoint is stable, complete, or safe to expose without a provider adapter.

The active server creates one anonymous locally generated session with `Innertube.create({ generate_session_locally: true })`. Moodify currently calls only `yt.music.search`, `yt.music.getInfo`, `format.decipher`, and its own HTTP wrappers.

## Client inventory

- Production server: `youtubei.js/web`; the default session client is created without an explicit `client_type`, while music methods issue `YTMUSIC` requests internally.
- Playback experiments: repository scripts explicitly exercise `YTMUSIC`, `WEB`, `MWEB`, `IOS`, `ANDROID`, and `TV` through `getBasicInfo`; `client-profiles.mjs` additionally probes `YTMUSIC_ANDROID`, `ANDROID_VR`, `TV_SIMPLY`, `TV_EMBEDDED`, `WEB_EMBEDDED`, and `VISIONOS`.
- These experiment clients are not production providers. Their output is diagnostic and may have different playability, format, authentication, and anti-abuse behavior.

Reliability reflects provider volatility and response completeness, not whether the JavaScript method exists.

## A. Playback

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Track playback info | `yt.music.getInfo(id)`; internally `watch` + `next` with client `YTMUSIC` | Yes, active | No for public tracks; playability varies | `TrackInfo`, `basic_info`, playability, formats, tabs, player/next data | Medium | Active source resolution and metadata seed |
| Decipher streaming format | `format.decipher(yt.session.player)` | Yes, active | No for public formats | Expiring direct media URL and format properties | Medium/volatile | Active media relay source |
| Media range relay | Moodify `/api/media/:id` plus upstream `Range` requests | Yes, active | Moodify-local endpoint; upstream rules apply | Byte ranges, content type/length/range | Medium | Browser-compatible playback |
| Watch history write | `TrackInfo.addToWatchHistory()` / `updateWatchTime()` | Library-supported | Yes/session state | Provider-side history mutation | Low until authenticated and tested | Do not use; local event log is source of truth |

## B. Search

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Music song search | `yt.music.search(query, { type: 'song' })`; `/search` with `YTMUSIC` | Yes, active | No | `Search2.songs` MusicShelf and item fields | Medium | Active search |
| Filtered music search | `yt.music.search(query, { type })` | Yes | No | Songs, videos, albums, artists, community playlists shelves when returned | Medium | Future provider adapter |
| Search suggestions | `yt.music.getSearchSuggestions(input)`; `/music/get_search_suggestions` | Yes | No observed guard | Parsed search suggestion sections | Medium | Future query assistance/discovery memory |
| General YouTube suggestions | `yt.getSearchSuggestions(query, previousQuery)`; suggest service | Yes | No | String suggestions | Medium | Not music-specific; defer |
| Search continuations | `Search2.getContinuation()` / `SearchContinuation.getContinuation()`; `/search` continuation | Yes | No | More MusicShelf items and next continuation | Medium | Future pagination |

## C. Discovery

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Music home feed | `yt.music.getHomeFeed()`; `browseId: FEmusic_home` | Yes | Anonymous method; personalization may require account | Music carousel/taste-builder shelves, filters, continuation | Medium/volatile | Candidate source after adapter validation |
| Music Explore | `yt.music.getExplore()`; `browseId: FEmusic_explore` | Yes | No observed guard | Navigation buttons and music carousel shelves | Medium/volatile | Charts/editorial candidate source |
| Music recap | `yt.music.getRecap()`; `browseId: FEmusic_listening_review` | Yes | Account/session likely | Recap response | Low until authenticated and fixture-tested | Not a base recommendation source |
| Explore mood/genre surfaces | Explore sections/buttons, not a stable Moodify taxonomy | Partial | No observed guard | Provider-labeled navigation/shelves when present | Low/volatile | Store provider labels as source context only |
| Hashtag feed | General `yt.getHashtag()`; `browse` FEhashtag | Yes, general YouTube | Usually public for public hashtags | Feed items | Low for music semantics | Not currently a music graph edge |

## D. User library

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Music library landing | `yt.music.getLibrary()`; `browseId: FEmusic_library_landing` | Yes | Account data requires authenticated session | Grid/MusicShelf contents, filters, sort options, continuation | Medium when authenticated | Possible provider-library import |
| General YouTube library | `yt.getLibrary()`; `browseId: FElibrary` | Yes | Account-dependent | General library object | Medium when authenticated | Not a music-specific source |
| Liked music | No dedicated verified `yt.music.getLikedSongs()` method in installed wrapper | No direct method | Would require account/session and endpoint validation | Unknown | Unknown | Do not claim support |
| Subscriptions | `yt.getSubscriptionsFeed()`; `browseId: FEsubscriptions` | Yes, general | Account-dependent | Subscription feed | Medium | Social/context candidate source only if user opts in |

## E. Playlists

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Public music playlist | `yt.music.getPlaylist(id)`; `browse` with `VL` ID | Yes | No for public playlist | Header, ordered `MusicResponsiveListItem` contents, background, continuation | Medium | Candidate graph and collection import |
| Playlist continuation | `Music Playlist.getContinuation()`; `/browse` continuation | Yes | Same as playlist | More playlist items | Medium | Preserve ordering and membership |
| Related playlists | `playlist.getRelated()` | Yes in wrapper | No observed guard | Related playlist carousel after continuation traversal | Low/volatile | Optional graph edge |
| Playlist suggestions | `playlist.getSuggestions()` | Yes in wrapper | Often account/context dependent | Suggested playlist items and continuation | Low | Defer |
| User playlist listing | `yt.getPlaylists()`; `FEplaylist_aggregation` | Yes | Account-dependent for private/user data | Feed of playlists | Medium when authenticated | Optional library import |
| Playlist creation/editing | `yt.playlist` manager; `/playlist/create`, `/browse/edit_playlist`, related mutations | Yes in library | Yes; methods enforce `session.logged_in` | Mutation response/status | Low until account tested | Not required for current intelligence |

## F. History

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Provider watch history read | `yt.getHistory()`; `browseId: FEhistory` | Yes in general wrapper | Account-dependent in practice | History object/feed | Medium when authenticated | Do not merge blindly with local events |
| Local listening history | Moodify `ListeningEventStore` | Yes, active | No | Bounded normalized events | High for local behavior | Current source of behavioral truth |
| Provider watch history write | `TrackInfo.addToWatchHistory()` / `updateWatchTime()` | Yes in wrapper | Yes/session | Mutation only | Low/untested | Defer |

## G. Related / up-next

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Track related content | `yt.music.getRelated(videoId)` or `TrackInfo.getRelated()`; `watch_next` then related tab | Yes | No observed guard | Parsed related content section | Medium/volatile | High-value candidate graph edge |
| Up-next queue | `yt.music.getUpNext(videoId)` or `TrackInfo.getUpNext()`; `watch_next` | Yes | No observed guard | `PlaylistPanel` with ordered queue items | Medium/volatile | Sequence/candidate source, not current queue |
| Up-next continuation | `TrackInfo.getUpNextContinuation(panel)`; `watch_next` continuation | Yes | No observed guard | More `PlaylistPanel` items | Medium/volatile | Future sequence capture |
| Automix | Built into up-next methods when no playlist ID exists | Partial | No observed guard | Provider-generated automix panel | Low/volatile | Treat as provider context, not Moodify preference |

## H. Metadata

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Track identity/title | `yt.music.search` item and `yt.music.getInfo` basic info | Yes, active | No for public tracks | Video/music ID and title | High/medium | Track DNA identity |
| Artists/authors | Search item `artists`; track detail author/channel and structured artist rows | Yes | No for public tracks | Names and sometimes channel/browse IDs | Medium | Track DNA relationships |
| Album | Search item `album` when present; `yt.music.getAlbum(id)` | Yes/partial | No for public album | Album ID/title and track shelves | Medium | Track DNA and graph |
| Duration | Search item duration; track detail `basic_info.duration`; media format duration | Yes, active | No | Seconds or format duration | Medium/high | Observable duration; validate conflicting values |
| Artwork | Search thumbnails; track detail thumbnail; album/artist headers | Yes, active/partial | No for public content | Thumbnail URLs | Medium | Display/cache only; not musical evidence |
| Release date/year | No stable field is normalized by current server | No current support | Unknown | Not exposed in Moodify response | Unknown | Never infer era from title text |
| Genre/subgenre | No stable field is normalized by current server | No current support | Unknown | Provider may expose labels on some surfaces, not a contract | Low | Leave unknown |

## I. Artist / album relationships

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Artist page | `yt.music.getArtist(artistId)`; `browse` artist ID | Yes | No observed guard | Header, music shelves/carousels, page | Medium | Same-artist graph |
| Artist top songs | `Artist.getAllSongs()`; Top songs shelf continuation | Yes in wrapper | No observed guard | MusicPlaylistShelf | Medium | Same-artist candidates |
| Album page | `yt.music.getAlbum(albumId)`; `browse` album ID | Yes | No observed guard | Header, track contents, sections, page URL | Medium | Same-album graph |
| Artist/album IDs from search | Search item endpoints | Partial | No | IDs if parser/source item contains them | Medium | Persist only when present |

## J. Recommendation signals

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Provider home recommendations | `yt.music.getHomeFeed()` | Yes/partial | Anonymous feed may be generic; personalized feed account-dependent | Shelves of provider candidates | Low/volatile | External candidate source, clearly labeled |
| Provider related recommendations | `getRelated` / `getUpNext` | Yes | No observed guard | Related/queued candidates | Medium/volatile | Candidate graph edges |
| Provider charts/explore | `yt.music.getExplore()` | Yes/partial | No observed guard | Provider shelves/buttons | Medium/volatile | Discovery candidate source |
| Moodify behavioral signals | `deriveListeningSignals(events)` | Yes, active | No | Track/artist/session aggregates | High/deterministic | Taste inputs |
| Provider user taste profile | No direct stable method audited for Moodify | No | Account likely | Unknown | Unknown | Do not replicate provider profile |

## K. Social / account signals

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| OAuth/device sign-in | `session.signIn(credentials)` and OAuth2 manager in library | Yes in library | Yes | Auth credentials/session state | Medium but security-sensitive | Not in current product path |
| Account overview | Account manager / account browse endpoints | Yes in library | Yes | Account metadata | Low until implemented/tested | Not needed for local-first taste |
| Subscriptions/channels | General feed methods | Yes | Account-dependent | Channel/feed items | Medium | Optional context, not taste fact |
| Moodify social listening | Local Moodify hooks/storage | Yes, separate | Moodify identity/session | Local shared sessions/messages | App-owned | Keep distinct from provider account data |

## L. Lyrics / transcripts

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Music lyrics | `yt.music.getLyrics(videoId)` / `TrackInfo.getLyrics()`; related music tab | Yes in wrapper | No observed guard; availability varies | `MusicDescriptionShelf` or provider message | Low/volatile | Optional text context; do not infer mood from lyrics automatically |
| YouTube transcript | General `VideoInfo.getTranscript()` | Yes for some videos | No observed guard; transcript availability varies | Transcript panel/segments | Low for music use | Not equivalent to lyrics; keep separate |

## M. Artwork

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Track thumbnails | Search/getInfo parser fields | Yes, active | No | URL plus size variants in raw response | Medium | Observable artwork URL |
| Album/artist/playlist artwork | Album/artist/playlist header/background fields | Yes/partial | Public content generally no | Thumbnail/background URLs | Medium | Content identity/context only |

## N. Pagination / continuations

| Capability | Endpoint / mechanism | Available? | Auth required? | Data returned | Reliability | Moodify use |
|---|---|---:|---:|---|---|---|
| Search continuation | `Search2.getContinuation` | Yes | Same as search | Next shelf page | Medium | Needed for large search context |
| Home continuation | `HomeFeed.getContinuation` | Yes | Same as feed | More shelves/items | Medium/volatile | Future discovery ingestion |
| Library continuation | `Library.getContinuation` | Yes | Same as library | More user library items | Medium | Optional authenticated import |
| Playlist continuation | `Playlist.getContinuation` | Yes | Same as playlist | More ordered items | Medium | Required for complete graph membership |
| Up-next continuation | `TrackInfo.getUpNextContinuation` | Yes | Same as up-next | More ordered queue items | Medium/volatile | Sequence graph |

## Bottom line

The highest-confidence anonymous capabilities are public search, public track info/playback, public artist/album/playlist pages, related/up-next, explore, and suggestions. Personal library, history, subscriptions, writes, and personalized feeds are account/session-sensitive even though the installed library exposes methods for them.

The current server exposes search, track lookup, media relay, and normalized read-only discovery routes backed by the provider adapter. Search, artist, album, playlist, related, up-next, Explore, and Home snapshots are persisted as bounded dated graph evidence. Search, playlist, Home, and up-next continuation handles are supported; artist, album, Explore, and related pagination remains limited by the installed wrapper's currently observed surface methods. This adapter is the backend boundary before any recommendation ranking is attempted.
