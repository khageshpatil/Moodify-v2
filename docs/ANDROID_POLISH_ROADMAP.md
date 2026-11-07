# Moodify Android: Polish Roadmap

*Branch `ux/phase-0-foundations` · written 2026-11-06*

This is the plan for everything after the "Aura" redesign. It comes from the UI/UX review (35 screens checked on the emulator), the code review, and the backlog we built up along the way. Each sprint ends with an APK that you test on your phone. Work is committed only after you've tested it, and pushed only when you ask.

Sizes are rough: **S** is under an hour, **M** is a few hours, **L** is most of a day.

---

## Step 0: Save the current work

Everything since the last push (28c7802) works on your phone but hasn't been committed:

- the fixes from the code review
- the "Play next" fix
- the big Now Playing card removed from Home
- automatic region detection
- the Discover blend
- lyrics matching fixes
- the artist-name fix
- 67 tests

**Plan:** commit and push this as one checkpoint, so every later sprint starts from a known-good state.

These are left out on purpose: the debug screenshots and XML dumps lying around the repo, `.impeccable/review/`, and the local Gradle tweaks (see Sprint 5.6).

---

## Sprint 1: Fix what's broken (P0)

> **Status: done 2026-11-06** (emulator-verified, 70 unit tests green, not yet committed). Also pulled forward: the new song-menu order (2.6), "Go to album", hiding sort chips for a single playlist (3.4), and not repeating the top result in Songs (3.5).
> **Changed from the plan:**
> - Songs credited to several artists open the first artist; there's no picker.
> - The "Search" key on the emulator's hardware keyboard doesn't submit; the on-screen keyboard does.
> **Found while testing (new):** the All search mixes in TV clips and fan videos (e.g. "Indian Idol"). Added to Sprint 3.5.

**Goal:** nothing in the app misleads or blocks you.

### 1.1 Search filters (M)
**Problem:**
- When you switch to a filter with no results, the old results stay on screen. For example, songs show up under "Artists".
- When a filter does have results, they show as one sideways row, with the second card cut off.
- The filter stays selected for the next search, so a recent search can open under "Albums".

**Fix:**
- Store each set of results against its search text and filter. Switching chips then shows a skeleton, then the results, or a clear "No artists for 'x'" message.
- Show filtered results as a vertical list, with more loading as you scroll.
- Typing a new search or tapping a recent search always resets the filter to **All**.

**Files:** `ui/screens/DiscoverScreen.kt` (Search screen), search state in `ui/MainViewModel.kt`.

**Done when:** each chip shows its own results or an empty message, never results from another chip.

### 1.2 Editing a search (S)
**Problem:**
- Tapping a box that already has text puts the cursor at the start, so typing gives "arijitsleep music".
- Those half-edited searches get saved to recent searches.
- Suggestions stay on screen after the keyboard closes.

**Fix:**
- Put the cursor at the end of the text when you focus the box.
- Save a recent search only when you actually submit it.
- Close suggestions when the keyboard closes. Back returns to the last results.
- Make sure the clear (✕) button is always there.

**Files:** `DiscoverScreen.kt`.

### 1.3 Artist name opens the artist page (M)
**Problem:** tapping an artist's name runs a text search, and the top result is a song.

**Fix:**
- Read the artist's ID from the catalogue data and store it on `Track`.
- Tapping the artist in the full player, a song row or a song's menu then goes straight to the artist page.
- If a song has no artist ID: search with the Artists filter and open the first artist.
- If a song credits several artists: show a small picker.

**Files:** `data/Track.kt`, `data/InnerTubeCatalogClient.kt`, `ui/player/FullPlayer.kt`, `ui/designsystem/TrackRow.kt`, navigation in `MainActivity.kt`.

### 1.4 Correct artist photo (S)
**Problem:** the artist page uses the cover art of the artist's first song, cropped into a circle.

**Fix:** use the photo YouTube Music provides for the artist page itself. Use song art only as a last resort.

**Files:** `InnerTubeCatalogClient.kt` (where the artist page is read), `ui/screens/DetailScaffold.kt`.

### 1.5 Accessibility (M)
**Problems and fixes:**
- **Hard-to-read text:** the Chill, Energize and Feel good tiles in Search put white text on pale colours. Add a darker overlay behind the text, or switch to dark text, so contrast is at least 4.5:1.
- **Cut-off text:** "15 songs" is clipped at the bottom of Library tiles. Give tiles a minimum height instead of a fixed one, and limit text to a set number of lines.
- **Screen reader leaks through the player:** with the full player open, TalkBack can still reach the screen behind it. Hide that screen from the screen reader while the player is open.
- **Touch targets:** check that all small icon buttons are at least 48dp.

**Files:** `DiscoverScreen.kt`, `ModernLibraryScreen.kt`, `ui/player/BottomChrome.kt` and `FullPlayer.kt`.

### 1.6 Navigation basics (S)
- Tapping the tab you're already on scrolls back to the top. Tapping it a second time goes back to that tab's first screen.
- Every item in the bottom bar gets a text label (Search and Library are missing theirs).

**Files:** `MainActivity.kt`, `ui/player/BottomChrome.kt`, and the scroll state on each screen.

**How to check Sprint 1:**
- Re-run every search scenario from the review on the emulator.
- Do a TalkBack pass with the player open.
- Screenshot the contrast fixes in light and dark mode.
- Then you test the APK on your phone.

---

## Sprint 2: Make it consistent (P1)

> **Status: done 2026-11-06** (emulator-verified).
> **Changed from the plan:**
> - Discover previews never pause your music. They play only while your music is paused (your decision).
> - The freed player button became **Save** (to playlist), because Like and Lyrics already had their own buttons.

**Goal:** the same idea works the same way everywhere, and Library holds what you care about.

### 2.1 One mood system (L)
**Problem:** "Chill" does three different things:
- Home's mood pills run a text search.
- Home's "Explore" chips are YouTube's own filters, including "Podcasts", which we filter out anyway.
- Search's grid is YouTube's "Moods & genres".

**Fix:**
- One list of Moodify moods (`ui/designsystem/Moods.kt`) used everywhere: Home pills, the Search grid and onboarding.
- Each mood opens one **Mood page**: a "For: Chill" header, Play all, Shuffle, and shelves built from YouTube's matching mood for your region, plus your own songs in that mood.
- Drop "Podcasts" and other chips that aren't music from Home.

### 2.2 Slimmer Home header (S)
**Problem:** the header takes up about 40% of the first screen. It has a greeting, a two-line headline, and a search bar that repeats the Search tab.

**Fix:** one line with the greeting and a profile/settings icon, then the mood pills. Music should appear on the first screen without scrolling.

### 2.3 Discover that you can hear (L)
**Problem:** you're asked to like or skip songs by looking at the cover only, and the ✕ / ▶ / ♥ buttons sit behind the mini-player.

**Fix:**
- **Previews:**
  - The top card plays a ~20-second preview from a good part of the song (about a third of the way in), fading in and out.
  - A separate lightweight player does this, so your queue isn't touched. Your music pauses while Discover is open and resumes when you leave.
  - A mute button controls whether previews play.
- **Layout:** the buttons sit above the mini-player and are always visible.
- **Undo:** an "Undo" for the last swipe.
- **After liking songs:** liked songs go to Liked, and a "Play what you liked" button appears.

**Files:** `ui/designsystem/DiscoverDeck.kt`, a new `player/PreviewPlayer.kt`, `MainViewModel.kt`.

### 2.4 Save albums, playlists and artists to Library (L)
**Problem:** Library only holds playlists you made. You can't keep any of the hundreds of playlists Home shows you. This is the biggest gap in the product.

**Fix:**
- Two new database tables (a Room v7 → v8 migration, with a migration test): `saved_collections` for albums and YouTube playlists, and `followed_artists`.
- A Save/Follow button on album, playlist and artist pages.
- Library filter chips: Playlists · Albums · Artists · Liked · Downloads · History.
- Saved albums and playlists open even when offline, if their songs are downloaded.
- Home gets a "From your library" shelf.

### 2.5 Player (M)
- **Where you're playing from:** "1 of 20" becomes "Playing from History", "Playing from Arijit Singh Radio", and so on. The queue remembers where it came from.
- **Sleep timer:** moves out of the four main buttons into the ⋯ menu. The freed spot goes to **Save/Like**, or to **Lyrics** if Like is already elsewhere.
- **Lyrics:**
  - Loading shows "Finding lyrics…" with placeholder lines instead of a bare spinner.
  - When nothing is found, the message says why, and offers a manual search.
  - The extra volume slider in lyrics goes; the phone's volume buttons already do this.

### 2.6 Menu order (S)
- **A song's menu:** Play next → Add to queue → Add to playlist → Like → Download → Start radio → Go to artist → Go to album → Share.
- **The player's ⋯ menu:** the actions above come first. Speed and pitch move under one "Playback speed" item, which opens its own small sheet.

### 2.7 Settings easy to reach (S)
A profile/settings icon in the Home header and in the Library top bar. Today Settings is only at the very top of Home.

**How to check Sprint 2:**
- Test the database migration on an emulator that has existing data, so no playlists or likes are lost.
- Check that each mood opens the same page from every entry point.
- Check that Discover previews start and stop correctly and never break your queue.
- Then you test on your phone.

---

## Sprint 3: Finishing touches (P2)

> **Status: done 2026-11-06** (emulator-verified).
> **Also done:**
> - Podcast episodes are filtered out of search.
> - YouTube's Home chips that duplicate a mood are hidden.
> - Playlist songs no longer show their length as part of the artist name.

**Goal:** it looks and feels deliberate, not generated.

### 3.1 Variety on Home (M)
**Problem:** Home is eight or more rows of identical square cards.

**Fix:**
- Mix the formats:
  - Quick picks as a compact song grid
  - one large highlighted card
  - artists as circles
  - wide cards for mixes and charts
- Use one header style for every row. Today our rows have a small label above the title and YouTube's rows don't.
- Fix wrong labels. For example, "Similar artists" sits above songs; it becomes "More like *X*".
- Show the first six YouTube rows, then load more as you scroll.

### 3.2 Settings overhaul (M)
**Pickers:** Country and Language become two rows that each open a searchable list, instead of 21 chips.

**New settings:**

| Setting | What it does |
|---|---|
| Audio quality | Auto / High / Data saver (which stream we pick) |
| Download quality | High / Normal |
| Download on Wi-Fi only | Downloads wait for Wi-Fi |
| Storage | Space used by downloads, the song cache, lyrics and the catalogue, with Clear buttons for each |
| Pause listening history | Plays aren't recorded, and "For you" stops learning |
| Clear history | With a confirmation step |

### 3.3 One main button colour (S)
**Problem:** the main button is red on an artist page, teal on Downloads, and purple elsewhere.

**Fix:** main actions always use Moodify Violet. Album art still colours backgrounds and glows.

### 3.4 Calmer Library (S)
- The three bright gradient tiles (Liked, Downloads, History) become quieter tiles with tinted icons, matching the album-art-led look.
- Sort chips only appear when there's more than one item to sort.

### 3.5 No duplicate search results (S)
- The top result isn't repeated as the first song.
- Results are de-duplicated by song ID, then by matching title and artist (for example "Whispers of Bliss" ×2).
- In the All results, push TV clips and fan uploads below official songs, or filter them out.

### 3.6 Motion, haptics and wording (S)
- One consistent animation for lists and artwork everywhere.
- Haptic feedback on like, swipe and long-press.
- A review of every empty and error message so each one says what happened and what to do next.

**How to check Sprint 3:**
- Screenshot every screen in light and dark mode, and at 200% font size.
- Then you test on your phone.

---

## Sprint 4: New features (pick what you want)

> **Status: done 2026-11-06**, except for **4.4 (home-screen widget)** and **4.5 (Android Auto)**. Both need new background-service code that can't be tested properly on the emulator, so they're deferred.
> - 4.1 shipped as "Your listening" (all-time play counts, not monthly).

Ideas that would make Moodify feel complete. Each one is separate, so pick any.

| # | Feature | Size | Notes |
|---|---|---|---|
| 4.1 | **Your month in music:** top songs, top artists, minutes listened, with a card to share | M | Built from play counts we already record |
| 4.2 | **Back up & restore:** playlists, likes and history to a file, and back | M | Lets you move to a new phone safely |
| 4.3 | **Open YouTube / YT Music links in Moodify:** "Share → Moodify" opens a song or imports a playlist | M | |
| 4.4 | **Home-screen widget:** cover, title, play/pause/skip | M | Built with Jetpack Glance |
| 4.5 | **Android Auto** support | M | The background playback service already provides part of what's needed |
| 4.6 | **Auto-download liked songs** on Wi-Fi | S | Depends on 3.2 |
| 4.7 | **Lyrics offline:** save lyrics together with each download | S | |
| 4.8 | **Equaliser button:** opens the phone's built-in equaliser for Moodify | S | |
| 4.9 | **Skip silence** option | S | |
| 4.10 | **Recently played** shelf on Home: albums and playlists, not only songs | S | |

---

## Sprint 5: Ready to release

> **Status: done 2026-11-06**, except for two items:
> - **The baseline profile (5.2) is deferred.** It needs its own benchmark module. The libraries' own start-up profiles already ship with the app.
> - **Cancelling a download mid-way** (5.5) is verified in code only. On the emulator, downloads finished before they could be cancelled.
>
> **Verified on the emulator:**
> - The 10 on-device tests pass. They caught a real bug: the lyrics cache purge was wiping imported legacy lyrics.
> - Repeat-one replays the song.
> - An interrupted download resumes and completes.
> - The first-run screens work.
> - The signed release build works.

### 5.1 Signed release APK (S)
- Create a signing key (you keep it, and it must be backed up: without it you can never update the app).
- A local `keystore.properties` file that is never committed. GitHub secrets so CI builds signed APKs too.

**Result:** a smaller, faster APK that you can share, instead of the debug build.

### 5.2 Faster start-up (M)
- A **baseline profile**, so Android pre-compiles the code that runs at start-up.
- Measure cold start on the release build. A debug build takes about 9 seconds on the emulator. The aim is under about 2 seconds on a mid-range phone.
- Check that R8 (the release-build code shrinker) doesn't break anything: catalogue, playback, downloads, lyrics.

### 5.3 Automated tests (M)
- Database migration tests v5 → v8, run on the emulator.
- A few UI tests: Back on every screen, opening and closing the player, the song menu actions, and the search filters.

### 5.4 Stability (S)
- Debug builds warn about slow work running on the main thread.
- Settings › Advanced gets a crash log that stays on the device. Nothing is sent anywhere unless you decide otherwise later.

### 5.5 Device checks still outstanding
You've confirmed most things on your phone. These still need one specific test each:
- Repeat-one replays the song.
- An interrupted download resumes, and a cancelled one cleans up after itself.
- The first-run onboarding screens, after clearing app data.
- The animation from a card to its detail page.
- Instant replay of a song that's fully cached.

### 5.6 Clean up and merge (S)
- Remove the loose debug screenshots and XML dumps from the repo folders, and add them to `.gitignore`.
- Decide whether to keep or revert the local Gradle tweaks (single worker, in-process Kotlin).
- Bump `versionName`, write a changelog, and open a PR from `ux/phase-0-foundations` into `main`.

---

## Order and decisions

**Recommended order:**
1. Step 0
2. Sprint 1
3. Sprint 2
4. Sprint 3
5. Sprint 5.1 (signed APK, so you can share good builds early)
6. Sprint 4 features of your choice
7. The rest of Sprint 5

**Decisions needed from you:**
1. **Discover previews:** is it OK that your music pauses while you're in Discover? (Recommended.)
2. **Player button freed by moving the sleep timer:** Like or Lyrics?
3. **Sprint 4:** which features you want, if any.
4. **Signing key:** ready to create one and keep it backed up?

**How each sprint is delivered:**
1. Build and run all tests.
2. Check on the emulator, with screenshots.
3. Install the APK on your phone.
4. You confirm.
5. Commit.
6. Push, only when you say so.
