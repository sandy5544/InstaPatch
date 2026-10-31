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

The selection policy is also expressed as a small, pure reference model in:

`patches/src/main/kotlin/app/morphe/patches/youtube/music/audio/OpusPreference.kt`

A standalone policy-model test script is provided at:

`tools/force-opus-policy-tests.kts`

The runtime bytecode hook implements the same decisions directly in the target methods. The reference model is not called by the injected bytecode, and its script is not currently part of CI.

## Experimental release

[Download Force Opus alpha.2 for YouTube Music 9.42.52](https://github.com/sandy5544/InstaPatch/releases/tag/force-opus-ytmusic-9.42.52-alpha.2)

The installable Morphe bundle is `patches-1.12.0-alpha.2.mpp`. Alpha.1 is superseded.

## Integration status

The Morphe bytecode patch is implemented in `patches/src/main/kotlin/app/morphe/patches/youtube/music/audio/ForceOpusAudioPatch.kt` and registered as `forceOpusAudioPatch`. It is pinned to YouTube Music `9.42.52` and adds fail-closed structural checks around the audio-only selection branch in `Lyop.n`.

The hook tracks the winning codec in a new `Lyon.patch_codec` field, initialized in the group constructor and updated only when an Opus candidate displaces a non-Opus candidate. It deliberately leaves `Lyon.b` unchanged because that field is used by the existing group sort order. When both candidates have the same Opus/non-Opus status, the original `Laipc` quality comparison remains unchanged. The global codec classifier and video-format path are untouched.

The patch is opt-in by default. Source compilation and metadata generation have passed on an earlier commit; the latest retained-codec-state changes require a fresh build. Applying the patch to the target APK, DEX verification, and on-device playback validation remain pending.

## Reverse-engineering notes

- `SelectableFormats` keeps video and audio candidates in separate repeated fields.
- `Lwgs.p(String)` already classifies the exact codec string `opus`; it should remain unchanged.
- `Lwgs.q()` supplies the quality ordinal and is not a codec indicator.
- `Lyop.n(...)` is the transformation and selection boundary.
- The selection policy must preserve the existing `Lwgs.A()` group key.
- The target APK has not yet been runtime-tested with this policy.

## Test

The policy test script is framework-free. Run it with a Kotlin scripting environment that has the compiled patches module on its classpath.
