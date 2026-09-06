# Moodify — Senior Product Design Review

Inspection basis: current implementation in `src/` (Home, Search, Library, Player, Queue, Discover, Social), Home/Personal State/Taste/Recommendation contracts, InnerTube adapter, and live product behavior. This is not a redesign brief. It is a verdict on whether the experience matches the personal-radio promise.

---

## 1. Executive Verdict

Moodify has built a real listening intelligence stack and then dressed it in three older products at once: a mood-prompt landing page, a Spotify-shaped library, and a YouTube Music discovery pipe. The radio already exists in code — listening events, taste, deterministic recommendations, and autoplay injection when the queue dies — but the user never gets a room that feels like radio. Home still leads with “Where are we going?” and four editorial scene cards. Continue is a last-track poster, not a thread. Mix is a sixth section that plays the same For You list. Social, Friends, Listen Together, and a ghost PRD about anonymous anime streaming still occupy chrome. The next build is not visual. It is to make returning Home equal **Continue the thread** then **start Mix**, and to stop rendering recommendation catalogues as if they were the product.

---

## 2. Product Definition

Moodify is a personal radio for one local listener. YouTube / InnerTube is the universe and the transport. Moodify’s job is to remember the thread, learn from what was actually heard, and choose the next song when the listener has handed over the queue.

The loop is Listen → Observe → Learn → Recommend or Discover → Listen.

That is not what the first screen currently sells. The live subtitle still says “Experience music through your emotions.” Intent, scene cards, and keyword-mapped mood playlists (`focus`, `chill`, `love`, `party`) sell a mood catalogue. Search, artist/album/playlist traversal, Explore, and provider Home sell a music browser. Library, playlists, import/export, and Social sell a management / community app. The intelligence stack is underneath all of that, mostly mute.

Treat the repository’s older PRD (privacy-first anime mood app, zero backend, collaborative listening as a north star) as historical. It contradicts the locked product. Do not let it keep driving navigation or copy.

---

## 3. Product Scorecard

Scores are experience scores, not engineering scores.

| Dimension | Score | Why |
|---|---:|---|
| Product clarity | 4 | First 10 seconds say “mood landing + four apps,” not radio. |
| First-minute UX | 4 | Cold start can reach a song, but through search-as-intent and fake moods. Returning start is a last-track replay, not a thread. |
| Returning-user UX | 3 | Continue cannot restore origin + remainder. Queue dies on refresh. |
| Cold-start UX | 5 | Intent + session cards can start something. They stay forever, so they never feel like a temporary onboarding. |
| Radio loop | 6 | Autoplay and Mix contract exist. Ownership change is invisible. Home still shows rec grids. |
| Discovery | 5 | Explore/Related/Up Next/artist traversal are real. Discover dumps provider Home. Queue mixes three “next” lists. |
| Search | 6 | Fast enough to be useful; still a mini catalogue (entities, More, playlists). Acceptable if Home stays radio. |
| Player | 5 | Mini-player is honest. Expanded player is controls + optional lyrics + related, with no sense of thread or Mix takeover. |
| Personalization | 4 | Taste and ranker are real; the user cannot feel who chose the next song. |
| Visual design | 6 | Dark editorial system with some craft; also leftover VisionOS pills, dual headers, and stock scene photos. |
| Information architecture | 3 | Two nav systems, Home labeled Moods, Social in primary nav, Discover vs Home vs Mix overlapping. |

---

## 4. Current User Journeys

### Cold start

1. Open app. Dual chrome: cinematic top bar (Home / Search / Library / Social) plus legacy brand line and, on some layouts, a “Moods” pill strip with Playlists, Queue, Favorites, Discover, Community, Friends.
2. Home: greeting, Intent composer (“Where are we going?”), four session cards (late train, focus, drive, old favourites), then empty Continue / Rotation until there is history.
3. User types a feeling or taps a card.
4. `handleIntent` does **not** ask Taste. It keyword-matches to hardcoded mood playlists, or searches InnerTube for the sentence, or plays favorites on “surprise / familiar.”
5. First song starts. Queue is those 10 search hits or the mood seed list. Mini-player appears.

Friction: the product pretends the typed sentence is understood. It is a search query or a canned playlist. Cognitive load is high before any music: too many doors, none labeled radio.

### Returning

1. Open app. Playback state is gone. Queue is gone. Preferences (volume) and library/history survive.
2. Home shows Continue as “Last time on Moodify” — typically last recently-played or last finished history track.
3. Click Continue. Playback starts that track with a **library** queue built from recently played / history items, not the unfinished search / artist / Mix remainder.
4. Below Continue: Rotation, For You, Fresh Finds, Mix, and still the full Intent + scene grid.

Friction: the user is asked to begin again while being shown a fake resume. For You and Fresh Finds compete with Mix. Intent still asks where we are going, as if they were new.

### Search

1. Type → autocomplete (readable strings) → Search.
2. Entity rows (artist / album / playlist) then songs. Play replaces the queue with the result list. Open entity → nested albums → More.
3. This path works as “I know what I want.” It is currently one of the few paths that feels finished.

Friction: Search is doing too much of the product’s emotional work because Home does not.

### Explore

1. Library shortcut or Discover nav.
2. Two provider shelves: Explore + YouTube Music Home, flattened to tracks.
3. Playing stamps `explore` or `home` discovery context (good evidence) but the UI is a catalogue, not a universe with a job.

Friction: Discover looks like a second Home. Provider Home on this surface is the clone leak.

### Queue exhaustion

1. User queue ends. Repeat off. Autoplay injects up to five recommendation candidates. Batch is stamped `recommendation`.
2. Nothing in the player or Home says Moodify now owns the night.
3. If the user closes the app here, Continue cannot resume that Mix remainder. The batch lives in a ref. Queue is not persisted.

Friction: the most important product moment is silent, then forgotten.

### Mix from Home

1. Mix button appears when the Home model has recommendation candidates (often the same list as For You).
2. Click plays For You tracks as a recommendation session.
3. For You and Fresh Finds still sit above or beside it as track grids.

Friction: Mix is not “one control.” It is a duplicate of a shelf.

### After several skips

1. Skip is recorded as listening evidence and Taste can move. The queue continues. Good.
2. No Explore nag. Good.
3. The user still has no idea the skip mattered. Next song just happens. If they skip by picking a different Search result, the whole thread is thrown away (`playTrack` clears the queue and the autoplay batch).

---

## 5. Information Architecture Audit

Primary jobs that should exist:

| Surface | Job |
|---|---|
| Home | Continue the thread, or start Mix, or (cold only) start from intent. |
| Search | Find a known thing. |
| Library | Memory: saved, playlists, history. |
| Explore | Deliberate universe browse. |
| Player | The radio itself. |
| Queue | The thread the user owns, until Mix takes over. |

What exists today:

- **Home** (`currentView === 'moods'`) — labeled Home in one nav and Moods in another. Intent + scenes + Continue + Rotation + For You + Fresh Finds + Mix. Too many jobs.
- **Search** — one job, slightly overweight, basically correct.
- **Library hub** — memory, plus a door to Explore (wrong place, but at least Explore is not Home).
- **Playlists / Favorites / History / Queue** — real memory and thread tools, duplicated in a second nav.
- **Discover** — Explore + provider Home catalogues.
- **Artist / Album / Playlist** — only reachable from Search (and graph), not first-class routes. Fine.
- **Social / Friends / Chat / Listen Together / Identity** — a second product.
- **Inspectors** — correctly gated to debug query params. Keep them out of the product.

Redundant: For You vs Mix vs Fresh Finds vs Discover vs provider Home. Dual navigation. Home view id still called `moods`.

Competing: Intent vs Continue on return. Related vs Up Next vs Queue vs autoplay Mix.

Dead ends: Social for a local-radio user. Scene contract unpublished but session cards shipped anyway.

Hidden: Mix ownership, skip-as-evidence, Taste. The engine is discoverable only if you know to look at debug inspectors.

Terminology: “Moods,” “set the scene,” “For you,” “Fresh finds,” “Up Next” (QueueManager heading is the user queue, then also provider Up Next). Users cannot tell Moodify Mix from YouTube’s next.

Catalog leaking into radio: Home rec shelves, Discover provider Home, Queue related/up-next modules, expanded player related list.

---

## 6. Home Audit

### Returning listener

First thing they see is still the Intent block and “Find a place to begin.” Continue is section 02, below four scene cards. Mix is section 06, after two recommendation lists.

Continue does **not** dominate. Mix is **not** understandable as the intelligent radio — it is a sparkles card that starts the For You pile. Everything competes: scenes, rotation, For You, Fresh Finds, a “there is a thread here” artist note that runs a search.

### Cold listener

Intent is useful as a start button. Session cards are a reasonable temporary affordance **if they disappeared after the user has a thread**. They do not. They are a permanent mood catalogue with stock photos and invented copy (`after hours`, `one more chapter`), despite Home architecture explicitly saying V1 publishes **zero** scenes and must not hardcode generic mood cards.

Keyword routing to `moodPlaylists` (The Weeknd as “chill”) is fake personalization. It trains the wrong expectation: Moodify understood the feeling. It did not.

### Overall

Home feels like **B) a dashboard** wearing **A) a catalogue**, with Spotify “For You” language and YouTube-adjacent discovery leftovers. It does not feel like **E) a personal radio**. Radio Home is two moves: resume the night, or let Moodify drive. Everything else is either cold-start scaffolding or another product.

The Home **data contract** is closer to the philosophy than the Home **UI**. `deriveHomeModel` already has prominence by cold-start tier. The UI ignores that discipline: it always renders Intent and scenes, and it materializes For You / Fresh Finds as track lists the architecture warned against treating as a catalogue.

---

## 7. Search & Discovery Audit

### Search

Autocomplete, entity rows, songs, More, artist/album/playlist traversal, fail-closed titles/artwork: this is the strongest user-facing InnerTube work. Play replaces the queue with the result set (user-owned queue — correct). It is a finder, not the radio.

Do not grow Search into Home. Do not add InnerTube Home shelves here. Hierarchy could be tighter (one artist, one album, then songs) but that is polish, not strategy.

InnerTube can still help Search without becoming a clone: better continuations, lyrics already on the player, richer entities. Stop before “Home from YouTube.”

### Discovery surfaces

| Surface | Why it exists | Who decides | Driven by | Useful evidence? | Visible? | Stay behind Explore? | Threatens radio? |
|---|---|---|---|---|---|---|---|
| Explore | Universe browse | Provider + user pick | User | Yes (`explore`) | Library / Discover | Yes | Only if it sits on Home |
| Provider Home | YouTube’s home | Provider | Provider | Weak for Moodify taste | Discover | Yes, or drop | **Yes** on Home; already a clone on Discover |
| Related | Sidecar around now-playing | Provider | User if they tap | Yes | Player + Queue | Related can live on player as a small “from this track” list | If it becomes the next-song UI |
| Up Next | Provider queue | Provider | User if they tap | Yes | Queue | Yes | High — named like Moodify’s job |
| Artist / Album / Playlist | Traversal when user knows the object | User | User | Yes | Search | Search/Library is enough | No |
| Moodify Mix / autoplay | Keep the thread | Moodify | Moodify after delegation | Yes (`recommendation`) | Should be Home + player | No — this is core | Only if shown as a shelf |

Provider recommendations must not become Moodify Home. Discover currently fetches both Explore and provider Home into track grids. That is YouTube Music with Moodify type.

---

## 8. Player Audit

The mini-player is the most radio-like object in the app: artwork, title, artist, transport, progress. It appears only when something is playing, which is correct.

The expanded player is a control surface. Lyrics (when InnerTube has them) are listening context, not Home clutter. Related tracks underneath reintroduce catalogue behavior at the moment the user should feel held.

Missing, and this is the feeling gap:

- No “next” from **this** thread.
- No distinction between *your queue* and *Moodify is driving*.
- No Continue affordance from the player (close the tab and the thread dies).
- Loading/errors exist in the engine; the player does not make waiting feel like radio (dead air vs a held breath).
- Queue is a separate page titled “Up Next,” then extra Related and provider Up Next lists. Three notions of next.

The user cannot tell why the next song is playing. Not because we lack explanation copy — we must not add score tooltips — but because Mix takeover is invisible and Related looks like the chooser.

Mobile: player reserve and safe scroll were patched. Dual nav still steals first-minute attention. Mini-player is the right persistent object; the pill strip is not.

---

## 9. Continue Audit

**Locked definition:** resume **track + origin + remainder**. History = finished listens. Not full session restore.

**What the code can do today**

| Piece | Survives refresh / close? | Notes |
|---|---|---|
| Last finished tracks | Yes | `moodify_history` via `appendFinishedListen` on `ended` |
| Recently played | Yes | Separate from history; includes starts, not only finishes |
| Incomplete listen | Partially | `resolveContinueListening` looks for unsettled `play_started` |
| Queue remainder | **No** | Queue is React state only |
| Origin / discovery context | **No** | Held in a `Map` ref, cleared on `playTrack` |
| Mix / autoplay batch | **No** | Refs only |
| Playback position | **No** | By design, if we refuse full session restore — acceptable if the *thread* returns |
| Volume | Yes | Envelope / localStorage |

Continue playback in the UI plays one presentable track and uses recently played or history as the queue, stamped `library`. That is “last played,” which the product lock forbids.

**What must change to make Continue trustworthy** (product, not a shopping list of features):

1. Persist one **open thread** in existing Personal State: current track identity, origin (`search` / `artist` / `playlist` / `recommendation` / …), ordered remainder, and whether Moodify owns the queue (user vs Mix).
2. Continue on Home plays that thread, not a recently-played mixtape.
3. Finished listens stay in History only. Do not use History as fake remainder.
4. Starting Search play, Mix, or an explicit new queue **replaces** the open thread. Skip inside the thread does not.
5. Do not restore seek position, shuffle animation, expanded player, or Social state.

Until this exists, returning Home is dishonest, and Mix-during-close is a broken promise.

---

## 10. Personalization Audit

The loop in code:

Listen (player) → events (`ListeningEventCollector`) → Taste + Track DNA → `generateRecommendations` → autoplay inject or Home Mix/For You.

The loop in experience:

Listen → …silence… → another song, or a For You row that looks like Spotify.

Where intelligence is invisible:

- Intent does not use Taste. It uses keywords and search.
- Home For You **exposes** ranked candidates as a catalogue, which is the wrong showing. Mix should **use** them as a session.
- Skip changes future scores but not present trust.
- “There is a thread here” + search for `{artist} songs` is a cute signal and a wrong action (Explore/search, not Mix).
- Inspectors prove the team can see the loop. Users cannot.

Do not retune weights. Do not add “because you skipped.” Feel it by: Continue that is true, Mix that starts the engine without a grid, and a player that does not panic when Moodify takes over.

---

## 11. Visual / Art Direction Audit

The living system (DM Sans / Instrument Serif, dark ground, frost, one cool accent, large type on Home) wants to be a quiet editorial radio. It is interrupted by:

- Legacy VisionOS pill navigation and “Experience music through your emotions.”
- Numbered dashboard sections (01–06) that make Home a report.
- Session cards with generic mood photography — cinematic Indian life was tried as atmosphere; here it reads as stock lifestyle UI.
- Glass cards and track rows repeating until the page is a feed.
- Discover/Queue still on older `glass-card` / white-text component kit — two visual products.

It is not yet generic SaaS, but it is adjacent: pills, badges, dual headers, inspector-era density. It is Spotify-like wherever For You / Fresh Finds appear. It is YouTube-like on Discover. “AI generated” risk is the Intent copy (“We’ll start the soundtrack”) plus mood keyword magic.

Cultural identity: do **less**. Artwork of the actual songs, the user’s language in Search, and editorial restraint will do more than train/monsoon cards. Do not push Devanagari, Hindi labels, or a Scene world until the radio loop is visible. Session cards already flirt with that world without earning it.

Motion is light. Empty states in Library are warmer than Home’s empty Continue (which simply omits the section). Mobile hierarchy is crushed by horizontal nav.

Do not redesign the skin next. Subtract competing surfaces and the existing type/color will start to mean radio.

---

## 12. Core vs Supporting vs Discovery vs Memory vs Noise

| Component | Class | Role |
|---|---|---|
| Playback + mini-player | CORE | The radio. |
| Open-thread Continue | CORE | Promise of return. **Not actually implemented.** |
| Moodify Mix (one control + autoplay inject) | CORE | Delegation. Contract exists; UI is a shelf duplicate. |
| Listening events, Taste, ranker, autoplay | CORE | Must stay; should not be shown as UI chrome. |
| Search + autocomplete + play-from-results | SUPPORTING | Start a user-owned queue. |
| Artist / album / playlist from Search | SUPPORTING | Finish a known-item path. |
| Queue (user remainder only) | SUPPORTING | Visible thread. |
| Lyrics | SUPPORTING | Context while listening. |
| Repeat / shuffle | SUPPORTING | User override; Mix must not fight them. |
| Explore | DISCOVERY | Universe, on purpose. |
| Related (small, on player) | DISCOVERY | “From this track,” user-driven. |
| Provider Up Next | DISCOVERY | Evidence; keep off Home; demote on Queue. |
| Provider Home feed | DISCOVERY / **NOISE on Home** | Clone if featured. |
| Favorites | MEMORY | Keep. |
| History (finished listens) | MEMORY | Keep; do not fake Continue. |
| Playlists + import | MEMORY | Keep secondary. |
| Library hub | MEMORY | Right place for memory. |
| Intent + session cards | SUPPORTING if cold-only; **NOISE if permanent** | Currently permanent. |
| For You / Fresh Finds track grids | NOISE | Catalogue of the ranker. |
| Hardcoded mood playlists + keyword routing | NOISE | Fake moods. |
| Social, Friends, Chat, Listen Together as primary | NOISE | Another product. |
| Download, identity-as-destination, dual nav, “Moods” label | NOISE | Leftover. |
| Debug inspectors | SUPPORTING for builders | Not product. |

---

## 13. What We Are Getting Wrong

We are using Home as a place to **display** intelligence instead of a place to **use** it.

We shipped a Home model that knows Continue ≠ Mix ≠ Scenes, then rendered Scenes as four always-on cards and Mix as a sixth button under two rec lists.

We defined Continue as a thread, then implemented last-played.

We built Mix ownership rules, then hid the takeover and threw the batch away on refresh.

We keep Social in the primary nav of a one-listener radio.

We still let a 2024 PRD (anime, moods, Listen Together, no server) share the UI with a 2026 InnerTube intelligence stack.

We ask the user “Where are we going?” after we already know where they were.

---

## 14. What We Should Stop Doing

- Rendering For You and Fresh Finds on Home.
- Publishing a permanent Scene / mood-card row.
- Mapping Intent keywords to `moodPlaylists`.
- Putting provider Home on a Discover page that feels like a second Home.
- Leading with Social.
- Calling Home “Moods.”
- Shipping two navigation systems.
- Treating recently played as Continue remainder.
- Adding InnerTube shelves because they exist.
- Adding explanation copy, score badges, or more cards to “show the AI.”
- Visual-world expansion (trains, monsoon, chai) before the loop is felt.

---

## 15. What We Should Preserve

- Local-first one-listener stance. Anonymous identity as a quiet profile, not a social graph.
- InnerTube as pipe: search, playback, artwork fail-closed, entity traversal, continuations, lyrics.
- Discovery graph + attribution on play (search vs artist vs recommendation vs explore).
- Listening events, finished-listen History, Taste Model, deterministic Recommendation Engine, autoplay injection rules (do not override user queue, repeat one/all).
- Skip as evidence without an Explore interrupt.
- Home **contract** sections and cold-start tiers (as data, not as six UI modules).
- Mini-player as the persistent radio object.
- Search as finder, Library as memory.
- Inspectors behind explicit debug flags.

---

## 16. What Is Missing

Only the gaps that break the promise:

1. **A true open thread** — the one Continue can trust.
2. **A returning Home that is Continue then Mix** — Mix hidden until there is enough listening context.
3. **A felt Mix takeover** — not a toast novel, just ownership in the player/queue when autoplay injects.
4. **Cold Home that is temporary** — Intent (and at most a few starts) until a thread exists, then it recedes.
5. **A single primary nav** — Home, Search, Library. Explore inside Library. Social out of the way.

Lyrics, autocomplete, and artist More are not the missing product. Continue is.

---

## 17. Recommended Product Direction

Make Moodify feel like a station that remembers the program.

When someone opens it with a night already in progress, the screen should be almost empty except the thread they were in and a way to say “you drive.” When someone opens it cold, they should start a program in one move, not browse a dashboard. When the songs they picked run out, the same program should continue, obviously under Moodify’s hand. When they search, they are interrupting with a request, not entering a store.

Stop proving the graph on Home. Spend the graph on the next song.

Reference without copying: Apple Music radio’s “the station continues”; a late-night FM show more than Spotify Home; YouTube Music only as the record warehouse. Unconventional web music players that let artwork and now-playing dominate are closer than any shelf UI.

---

## 18. The One Next Commitment

**Make Continue a real open thread, and make returning Home only Continue + Mix.**

Persist one thread in Personal State: track, origin, remainder, owner (user or Mix). Continue resumes it. Mix starts the recommendation session as **one control**, using the engine you already have — no For You grid. Hide Mix on true cold start. Move Intent/session cards to cold-only. Do not retune the ranker. Do not redesign visuals. Do not add InnerTube Home.

If this ships and returning still feels like a catalogue, the philosophy is still failing. If this ships and opening Moodify feels like walking back into the same night, the rest of the product can get quieter on purpose.

---

## 19. Explicitly Deferred Work

- Any visual-system or “Indian everyday life” art-direction pass.
- Scene catalogue / editorial worlds.
- Provider Home or Explore on Moodify Home.
- Social, Friends, Listen Together as core.
- Recommendation weight changes, explanation UX, eval dashboards in the product.
- Playlist-product expansion, downloads, identity as a destination.
- Search cosmetics beyond what already finds a song.
- Full playback-position session restore.

---

## 20. Design Principles

1. **Radio first.** If a screen does not start, continue, or hold a listening thread, it is not Home.
2. **YouTube is the warehouse.** Moodify is the DJ. Never present provider Home as our judgment.
3. **One thread.** Continue is track + origin + remainder. History is what already ended.
4. **Delegation is visible.** When the user queue ends, Moodify owns the night; say that with behavior, not a spreadsheet.
5. **Show intelligence by choosing, not by listing.** Mix is a control. Ranked shelves are a catalogue.
6. **Cold start is scaffolding.** Intent and scenes must recede once a thread exists.
7. **Search finds. Library remembers. Explore is optional.** Do not let any of them eat Home.
8. **Skip is a whisper, not a fork.** It teaches the next choice; it does not abandon the program or open a tour.
