---
version: 1
slug: "pplication-ui-screens-modernhomescreen-kt-a8375f95"
primary_target: "android/app/src/main/java/com/example/myapplication/ui/screens/ModernHomeScreen.kt"
related_targets: []
---

# Moodify Android Home

## Scope and mode

Replace the active Android homepage at `android/app/src/main/java/com/example/myapplication/ui/screens/ModernHomeScreen.kt`. Mode: Operate. This is a phone-first listening surface, not a marketing hero.

## Audience, job, action, proof, constraints

- Audience: a listener opening Moodify on Android to find something to play quickly.
- Job: understand the current discovery context, choose a mood or intent, scan live music shelves, and start playback.
- Primary action: play a track or submit an intent.
- Proof/content: provider-backed quick picks, catalog shelves, track artwork, artist metadata, loading and empty states.
- Constraints: preserve `onIntent`, `onTrackSelect`, `onBrowseClick`, current playback state, Material 3, system Back, edge-to-edge layout, light/dark themes, and minimum 48 dp targets.

## Chosen direction and memorable moment

Chosen direction: Signal Shelf, a compact native music-client feed. Moodify keeps a restrained dark/light Material surface, strong artwork, dense but breathable rows, and ordinary Android controls. The first viewport starts with the action and immediately proves the catalog with playable quick picks. Signature interaction: selecting a track promotes it into the persistent mini-player and marks the row as active without taking the listener away from the feed.

The shell uses a standard Material top app bar, compact filter chips, a vertically scrolling feed, horizontal catalog shelves, and a Material navigation bar. No decorative world artifact replaces navigation or controls.

## First viewport contract

The first screen must show: Moodify top bar; one concise listening prompt with the intent field and submit action; a horizontal row of discovery chips; a `Quick picks` section with real track rows and play affordances; and the beginning of the next live catalog shelf. The empty/loading state must preserve this rhythm with clear feedback.

## Unresolved decisions

- The provider may return no playable tracks on a cold start; keep the catalog shell visible and show a recoverable loading or empty state.
- The current product does not expose a full local playlist model in the Android homepage; do not invent playlist creation in this pass.

## Seed reference

seed:7e4d7fbaedf13f25
