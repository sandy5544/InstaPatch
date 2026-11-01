# YouTube Music 9.42.52 APK analysis

## Artifact identity

- Package: `com.google.android.apps.youtube.music`
- Version name: `9.42.52`
- Version code: `94252240`
- minSdk: 26
- targetSdk: 36
- DEX files: `classes.dex` through `classes6.dex`
- APK SHA-256: `9c38d63907362715f6172545bcb36f11ddbd45f014938626068016f06dcc3c56`
- ABI payload includes `arm64-v8a` libraries, including `libmedia3EffectJNI.so`, `libopusV2JNI.so` and `libvpxV2JNI.so`.

The package/version values were read from the uploaded APK's binary Android manifest. The SHA-256 identifies this exact uploaded file, not every APK distributed under the same version string.

## Confirmed playback architecture symbols

The APK contains AndroidX Media3/ExoPlayer classes and YouTube-owned playback infrastructure. The following obfuscated symbols were identified in the DEX method/type tables:

| Symbol | Confirmed signature / role from DEX metadata |
|---|---|
| `Lyza.a` | Instance method `(Lyyv; Lccw; I) -> Landroidx/media3/exoplayer/ExoPlayer;`; a YouTube-side ExoPlayer creation/factory path |
| `Lcbt.a` | Instance method `() -> ExoPlayer`; called by `Lyza.a` |
| `Lccm` | Concrete class extending `Lbrs`; ExoPlayer-facing implementation/delegation layer |
| `Lyzo` | Constructed with an ExoPlayer instance; contains surface, playback and lifecycle coordination methods |
| `Lyzz` | Contains methods accepting ExoPlayer and track/queue-related objects |
| `Lcom/google/android/libraries/youtube/player/background/service/BackgroundPlayerService` | Manifest-declared background playback service |
| `Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService` | YouTube Music media-browser service |
| `Lyop.n` | Existing Force Opus patch target: audio-format candidate selection method |

These are version-specific obfuscated symbols. Do not reuse them for another YouTube Music version without re-analysis.

## What this establishes

1. The uploaded file is the exact `9.42.52` target expected by the existing Force Opus patch.
2. A YouTube-owned ExoPlayer factory path exists. This is a better investigation point for a future audio-output integration than attempting to create an unrelated player.
3. The player-facing classes are heavily obfuscated and spread across multiple DEX files. A safe patch needs structural fingerprints and register/control-flow checks, not just class-name matching.
4. The APK includes Media3 effect JNI support, but its presence alone does not prove that a public or safe PCM processor insertion point is available to a Morphe patch.

## Not yet proven

- No decoded PCM callback or stable AudioProcessor insertion point has yet been demonstrated in the target player factory.
- No secondary player has been instantiated or connected to YouTube Music's MediaSession/queue lifecycle by this branch.
- No external FLAC/ALAC source resolver has been integrated. Do not intercept or reuse authenticated/DRM-protected YouTube stream URLs or credentials.
- No Automix, tempo stretching, EQ, echo, beat matching or source module is yet active in the patched app.
- The uploaded APK has been inspected, not modified, resigned, installed or exercised on a device.

## Next engineering steps

1. Trace the complete `Lyza.a` and `Lcbt.a` control flow, including the concrete `Lccm` implementation and its audio output/session ownership.
2. Identify whether the player is built with a custom RenderersFactory/AudioSink and whether the builder can accept a supported audio processor chain before `build()`.
3. Inspect the existing Morphe crossfade implementation and reuse its player-factory/session handoff model rather than layering a third player.
4. Prototype a single feature with a fail-closed fingerprint, then verify generated DEX and install/playback behavior on a device.
5. Keep source-module work separate and opt-in; require explicit source configuration and never bypass DRM, account authorization or subscription entitlement.

## Safety and release gate

Do not distribute a patched APK. Keep the user-provided original APK local to analysis; publish only patch source and metadata. A feature is not complete until it is applied to the exact APK, DEX-verified and tested on-device for normal playback, pause, seek, skip, queue changes, background playback, Bluetooth, Cast, Android Auto and long sessions.