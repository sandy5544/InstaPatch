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

This commit adds and tests the selection policy only. It does **not** register a Morphe bytecode patch yet and does not modify YouTube Music DEX instructions.

The remaining integration task is to anchor the policy at the audio-only candidate replacement site in `Lyop.n`. The patch must prove the candidate and stored-wrapper registers, the existing group key, and the exact branch that decides whether to replace the current candidate. Until those anchors are verified against the target DEX, the patch is intentionally not exposed in the Morphe patch list.

This avoids shipping a patch that appears selectable but either does nothing or changes video selection, languages, or decoder capability checks.

## Reverse-engineering notes

- `SelectableFormats` keeps video and audio candidates in separate repeated fields.
- `Lwgs.p(String)` already classifies the exact codec string `opus`; it should remain unchanged.
- `Lwgs.q()` supplies the quality ordinal and is not a codec indicator.
- `Lyop.n(...)` is the transformation and selection boundary.
- The selection policy must preserve the existing `Lwgs.A()` group key.
- The target APK has not yet been runtime-tested with this policy.

## Test

The policy test script is framework-free. Run it with a Kotlin scripting environment that has the compiled patches module on its classpath.
