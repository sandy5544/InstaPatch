# Auralis Unified

A standalone Android music-player project combining the Android-native playback and provider architecture of [LastWave](https://github.com/pm-official-id/Your-LastWave) with the BitChord-inspired player experience already ported into that Android codebase.

## Project layout

- `lastwave/` is a pinned Git submodule containing the full Android application source, Gradle wrapper, core modules, provider modules, database, UI, and tests.
- The upstream Android source already contains a dedicated Jetpack Compose BitChord player style (`ui/player/bitchord`) and its supporting components.
- Playback remains native Android Media3/ExoPlayer with LastWave's stream-resolution stack. The desktop BitChord Electron/Shaka engine is not copied into Android because it depends on browser MediaSource, Electron session bridges, and TypeScript runtime APIs.

## Integrated capabilities inherited from the Android base

- YouTube Music discovery and native InnerTube/NewPipe-based audio extraction.
- Provider and audio-source architecture, including source fallback and stream metadata.
- Media3 playback, queue, background playback, media session and notification controls.
- BitChord-style full-screen player, queue presentation, lyrics presentation and motion/gesture treatment.
- Local music library, downloads, metadata, artwork, lyrics and player settings from the Android base.
- Optional provider modules and the existing LastWave module framework.

Pinned Android source revision: `7028d286acfe8245270a6936e33b4d8006a53df5` (2026-11-04). The BitChord desktop reference inspected for this integration is `d0512d3c2eb839276a356e5ab5ffcc03b127aba9`.

The precise feature set is determined by the pinned upstream commit. No encrypted third-party module keys, credentials, or proprietary services are embedded by this wrapper.

## Build

GitHub Actions checks out the pinned submodule recursively, installs JDK 21, and builds the GMS mobile ARM64 debug APK:

```bash
git clone --recurse-submodules -b auralis-unified https://github.com/sandy5544/InstaPatch.git
cd InstaPatch/auralis-unified/lastwave
./gradlew :app:assembleGmsMobileArm64Debug
```

The workflow publishes the APK as a run artifact and a uniquely versioned GitHub prerelease. The build does not require private signing keys.

## Upstream and licensing

- Android base: [pm-official-id/Your-LastWave](https://github.com/pm-official-id/Your-LastWave), GPL-3.0.
- BitChord desktop reference: [britonmearsty/BitChord](https://github.com/britonmearsty/BitChord), GPL-3.0.
- BitChord-style Android player component: attribution and adaptation notes are retained in the upstream source files.
- The upstream source is pinned as a Git submodule. Its license and copyright notices must be preserved in any redistributed build.

## Porting boundary

BitChord desktop is Electron + React + TypeScript. Its YouTube engine uses browser MediaSource Extensions, Shaka Player, SABR, BotGuard/WebPO and an Electron main-process authentication bridge. Those components cannot be dropped into a native Android app unchanged. Auralis Unified uses the Android base's native stream repositories and player lifecycle, while adopting the BitChord-style Compose player. This avoids shipping a nonfunctional Electron runtime inside Android.
