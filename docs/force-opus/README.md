# Force Opus for YouTube Music

Target application: YouTube Music  
Target package: `com.google.android.apps.youtube.music`  
Initial target version: `9.42.52` (version code `94252240`)

## Intended behavior

Within each existing audio-format group:

1. Prefer an Opus candidate whenever that group contains one.
2. If multiple Opus candidates exist, keep the candidate with the highest existing quality ordinal.
3. If no Opus candidate exists, retain the existing quality-based fallback.
4. Keep groups independent so alternate languages and commentary tracks are not collapsed.
5. Never modify the video-format list or the global codec classifier.

The pure selection policy is implemented in:

`patches/src/main/kotlin/app/morphe/patches/youtube/music/audio/OpusPreference.kt`

A small policy test script is provided at:

`tools/force-opus-policy-tests.kts`

## Integration status

The Morphe bytecode patch is implemented in `patches/src/main/kotlin/app/morphe/patches/youtube/music/audio/ForceOpusAudioPatch.kt` and registered as `forceOpusAudioPatch`. It is pinned to YouTube Music `9.42.52` and adds fail-closed structural checks around the audio-only selection branch in `Lyop.n`.

The injected comparison prefers Opus over non-Opus within the existing group. If both candidates use the same codec class, the original `Laipc` quality comparison remains unchanged. The global codec classifier and video-format path are untouched.

Build validation and on-device playback validation are still pending. The CI workflow is being used to compile the Morphe patch module; a successful compile does not by itself prove that the patched APK plays correctly.

## Reverse-engineering notes

- `SelectableFormats` keeps video and audio candidates in separate repeated fields.
- `Lwgs.p(String)` already classifies the exact codec string `opus`; it should remain unchanged.
- `Lwgs.q()` supplies the quality ordinal and is not a codec indicator.
- `Lyop.n(...)` is the transformation and selection boundary.
- The selection policy must preserve the existing `Lwgs.A()` group key.
- The target APK has not yet been runtime-tested with this policy.

## Test

The policy test script is framework-free. Run it with a Kotlin scripting environment that has the compiled patches module on its classpath.
