# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Inferred from the request and current app: people who want to discover and play music from YouTube Music on an Android phone, often while moving between listening, search, catalog browsing, and the system media controls.

## Product Purpose

Moodify turns a feeling, moment, or music query into a playable listening session. Success means a user can open the app, understand what to do, discover music quickly, start playback, and continue managing the session from the mini-player, lock screen, or notification.

## Positioning

Moodify combines mood-led discovery with a typed music catalog and native Android playback. Its distinct product foundation is the combination of intent-driven listening, regional/provider catalog shelves, and a native playback surface rather than a web player.

## Operating Context

The primary workflow is phone-first and listening-led: open Home, choose a mood or describe a moment, browse quick picks or catalog shelves, play a track, then continue through the queue, mini-player, notification, or full-screen player.

## Capabilities and Constraints

- Home, search, catalog shelves, browse/detail pages, favorites, queue, history, playlists, lyrics, downloads, and native background playback already exist in the codebase.
- Home data comes from Moodify's provider/catalog layer and items can contain track metadata plus browse endpoints.
- The homepage redesign must preserve existing callbacks for intent submission, track selection, catalog browsing, and playback.
- Android UI uses Jetpack Compose and Material 3. Touch targets, system Back, edge-to-edge insets, light/dark themes, and notification/media-session behavior must remain native and functional.
- No backend or playback architecture changes are in scope for this homepage redesign.

## Brand Commitments

- The product name is Moodify.
- The user wants a polished, ArchiveTune-inspired music-client experience, while Moodify's own product identity and functionality remain intact.
- The homepage should feel content-first, musical, calm, and immediately playable rather than like a marketing landing page.

## Evidence on Hand

- Existing Android homepage: `android/app/src/main/java/com/example/myapplication/ui/screens/ModernHomeScreen.kt`
- Existing Android shell and navigation: `android/app/src/main/java/com/example/myapplication/MainActivity.kt` and `android/app/src/main/java/com/example/myapplication/ui/components/BottomNavBar.kt`
- Existing catalog components: `android/app/src/main/java/com/example/myapplication/ui/components/CatalogShelves.kt`
- Existing Android screenshots/build output and emulator available in the workspace.
- The local ArchiveTune source and screenshots are reference evidence for interaction patterns only, not source code to copy.

## Product Principles

- Put playable music in the first viewport.
- Make discovery legible before making it expressive.
- Preserve native Android expectations and accessibility.
- Every visible control must lead to a working Moodify action or a clear state.
- Let the catalog provide the content; the homepage provides the rhythm.

## Accessibility & Inclusion

Use Material 3 roles and components, minimum 48 dp touch targets, readable system-scaled text, content descriptions for meaningful icons, visible loading/error states, light/dark contrast, and predictable system Back behavior.

> Context facts above were inferred from the explicit redesign request and the existing Moodify implementation because no structured interview surface was available in this mode.
