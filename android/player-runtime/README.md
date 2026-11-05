# Android YouTube player-script runtime

This isolated JavaScript package creates the Android asset
app/src/main/assets/player/youtubei-player-runtime.js. It uses the MIT-licensed
youtubei.js AST analyzer to extract YouTube's signature and throttling
transforms; the remote player file is parsed and only extracted transform code
is evaluated by QuickJS.

## Rebuild

From the Android project directory:

    npm --prefix player-runtime ci
    npm --prefix player-runtime run build

The build also writes the full third-party license texts to
app/src/main/assets/licenses/player-runtime-third-party-notices.txt. The
About screen exposes those notices.

## Runtime constraints and evidence

- Runtime dependency versions are pinned in package.json and package-lock.json.
- The current Android QuickJS heap cap is 384 MiB, with a 60-second evaluation
  timeout and at most two downloaded player scripts held in the Kotlin cache.
- A live API 37 emulator test passes through the production resolver for both
  signature and n transforms with YouTube's current approximately 2.6 MiB
  player script. A 320 MiB cap failed; 352 MiB passed in the same emulator.
- PO-token generation is not implemented. Anonymous playback profiles that
  require a PO token can still reject playback, and physical-device memory and
  playback remain to be verified.

Do not add ArchiveTune GPL sources or assets to this package. Its architecture
was studied independently; this runtime uses the upstream MIT package.
