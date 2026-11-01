# BitChord-to-Morphe port: engineering plan and validation boundary

## Goal
Bring selected BitChord audio capabilities into YouTube Music through a Morphe-compatible patch source while preserving YouTube Music's own account, queue, stream and DRM handling.

The repository currently contains a YouTube Music Force Opus patch pinned to 9.42.52. Initial runtime work must remain pinned to that exact app build. Every new hook must be version-fingerprinted and fail closed when target bytecode differs.

## Architecture finding
BitChord is a standalone Media3/ExoPlayer application. Its transition DSP runs inside its own playback service and audio processors. YouTube Music owns its player lifecycle, stream resolution, DRM/data-source setup and audio sink. Copying BitChord player classes into a Morphe extension does not connect them to YouTube Music.

Morphe already has a dual-player crossfade implementation. The safe route is to extend/reuse that runtime architecture, not create a second independent player stack.

## Port status
| Capability | Status | Required integration |
|---|---|---|
| Existing Force Opus | Present, opt-in, YTM 9.42.52 | Apply to exact APK and verify DEX + playback |
| Improved crossfade | Morphe already has dual-player crossfade | Extend only after reviewing existing hooks and regression behavior |
| BPM / beat matching | DSP analysis prototype added | Feed PCM into analyzer; connect plan to transition controller |
| Advanced Automix | Not runtime-integrated | Queue look-ahead, beat grid, safe tempo control and transition state machine |
| EQ / audio filters | Biquad DSP primitive added | Requires a supported audio-sink/processor hook |
| Echo-out | Echo DSP primitive added | Requires audio-thread integration, lifecycle and real-time allocation tests |
| FLAC / ALAC sources | Not implemented | Requires source resolver, URL/auth lifecycle, cache and format integration |
| Smart quality selection | Force Opus is only a codec preference | Requires stream selection hooks and network/quality policy |
| Custom source modules | Not implemented | Requires sandbox, allow-list, network policy, parser, timeout and secret handling |

## DSP prototype
BitChordAudioEngine.kt independently implements:
- onset-envelope BPM estimation with autocorrelation;
- conservative beat-aware transition planning;
- stateful biquad filtering;
- bounded multichannel echo-out processing.

It is not yet called by the patched YouTube Music runtime. It is a testable algorithm foundation, not a claim that these features are active in YouTube Music.

Run:
./gradlew :patches:testBitChordAudioEngine

## Implementation sequence
1. Validate current Force Opus against the exact YTM 9.42.52 APK and keep it opt-in.
2. Reverse-engineer Morphe's existing crossfade runtime hooks for this target; document player lifecycle, player factory, queue advance and cast/video-mode restrictions.
3. Test beat-grid analysis against representative PCM fixtures, then connect it to transition timing without changing stream resolution.
4. Add filters and echo only if the target audio sink exposes a safe insertion point. Avoid blocking or allocating on the audio thread.
5. Add settings only after the runtime hook is stable.
6. Treat external FLAC/ALAC and custom modules as a separate high-risk project. Do not intercept authenticated or DRM-protected stream URLs or export session credentials. External sources must be explicitly configured and independently validated.
7. Expand supported YTM versions only with fresh APK fingerprints and device tests.

## Release gate
Do not label a release stable until the Morphe bundle compiles, the patch applies to the exact APK, DEX verification succeeds, playback/pause/seek/queue/background behavior passes, cast/Android Auto/video mode/Bluetooth routes are checked, and long-playback testing shows no glitches, underruns, ANRs or leaks.

A successful Kotlin compile or DSP smoke test does not prove that an injected runtime patch works.
