# BitChord feature-port inventory for InstaPatch

**Scope:** audit the public BitChord feature set against what can be delivered as a Morphe patch to the existing YouTube Music APK. This is an implementation checklist, not a claim that every item is already active.

**Reference project:** https://github.com/kushagrasinghx/BitChord  
**Reference license:** GNU GPL-3.0-or-later. InstaPatch is also GPL-3.0; any code adapted from BitChord must retain copyright/license notices and the corresponding source obligations. This inventory does not copy BitChord source files.

## Core playback and audio engine

| BitChord capability | Morphe-port assessment | Work required |
|---|---|---|
| Gapless playback | Potentially patchable | Verify YTM queue/renderer transition and preserve existing playback semantics |
| True crossfade (0–12 s) | Partially available in Morphe ecosystem | Inspect and extend the existing dual-player implementation; exact YTM player-factory hook required |
| Equal-power crossfade curves | DSP foundation exists | Connect to both player volume paths and validate clipping/headroom |
| DJ-style Automix | Not integrated | Queue look-ahead, analysis cache, transition state machine, fallback policy |
| BPM/beat-grid analysis | Prototype only | Decode representative audio off-thread; validate BPM confidence and phase |
| Beat matching | Planner prototype only | Feed analysis to transition controller and verify real output timing |
| Tempo stretching | Not implemented | Requires a time-stretch processor or safe playback-parameter hook; bound CPU and artifacts |
| Phrase/section alignment | Not implemented | Requires structural analysis and transition cue selection |
| Musical-key compatibility | Not implemented | Requires key estimation and a conservative compatibility policy |
| Vocal activity detection | Not implemented | Requires model/runtime and validated model assets |
| Bass handoff/filter sweeps | EQ/filter primitives only | Integrate synchronized filters into the audio path |
| Echo-out transitions | DSP prototype only | Integrate on the audio thread with lifecycle and allocation tests |
| Loudness normalization | Not implemented | Per-track loudness analysis and gain/headroom management |
| Peak limiting / clipping protection | Not implemented | Safe processor insertion and clipping regression tests |
| Multi-band/system EQ | Biquad primitive only | Settings, presets, per-channel state and processor integration |
| Audio filters | Biquad primitive only | Runtime routing for low-pass/high-pass/peaking filters |
| Playback speed (0.5x–2x) | YTM may already expose a speed control | Preserve native control; do not duplicate without a stable hook |
| Skip silence | Not implemented | Silence detection and safe seek/renderer integration |
| Sleep timer | Not implemented | Service-level timer and stop-after-current-track handling |

## Source resolution, quality and local audio

| BitChord capability | Morphe-port assessment | Work required |
|---|---|---|
| External FLAC/ALAC source modules | Not implemented | Source API, explicit user configuration, matching and format negotiation |
| YouTube Music fallback | Native YTM source remains authoritative | Fallback only for user-configured, lawful sources; never hijack authenticated YTM URLs |
| Source registry and priority | Not implemented | Allow-listed registry and deterministic source ordering |
| Source resolver | Not implemented | Resolve only explicitly configured source endpoints |
| Track matching | Not implemented | Match title, artist, album, duration and recording identity; reject ambiguous matches |
| Source health checks | Not implemented | Timeouts, health state, bounded retries and clear diagnostics |
| Custom source modules | Not implemented | Sandboxed declarative modules; no arbitrary code execution in the YTM process |
| Smart Wi-Fi/mobile quality ceilings | Force Opus is a codec preference, not a quality policy | Network-aware quality policy and stream-selection hook |
| Fast-start then quality upgrade | Not implemented | Safe replacement without interrupting active playback |
| Stream format/codec selection | Force Opus patch exists separately | Add exact-version fingerprinted policy and preserve YTM fallback |
| FLAC/ALAC decoding | Depends on YTM's supported Media3 decoder path | Verify codec/container support before selecting a source |
| Offline downloads with metadata | Native YTM download system exists | Do not bypass account, entitlement or DRM controls; only extend lawful local-file workflows |
| Local music library | Not a small patch feature | Requires MediaStore/library UI and playback integration beyond current patch scope |
| Cache/rendition management | Not implemented | Bounded cache policy and protection against mixed/corrupt renditions |
| Audio quality/stats display | Limited patch feasibility | Add only where YTM exposes stable UI/view-model hooks |

## Experience, lyrics and integrations

These features are part of BitChord as an application, but are not equivalent to audio-engine features. A patch can only add them where YouTube Music has stable, patchable UI and service hooks; replacing the host app's navigation or product architecture is out of scope for a conventional Morphe patch.

| BitChord capability | Morphe-port assessment |
|---|---|
| Animated album canvas / motion artwork | UI and media-source feature; not in current patch |
| Word/syllable-synced lyrics | UI/provider feature; not in current patch |
| Multiple lyrics providers | UI/network feature; not in current patch |
| Artwork-driven dynamic theming | App-wide UI architecture; not a practical isolated patch |
| Frosted-glass UI / Material 3 redesign | App-wide UI architecture; not a practical isolated patch |
| Discord Rich Presence | Separate account/integration service; not in current patch |
| Spotify account/playlists | Separate account/API integration; not in current patch |
| Last.fm / ListenBrainz scrobbling | Separate network integration; requires user opt-in and credential handling |
| Listen Together | Distributed backend, sync protocol and queue ownership; not a safe small patch |
| Android Auto | Host app media browser/session feature; preserve native YTM implementation |
| Chromecast / Cast | Host app routing/session feature; preserve native YTM implementation |
| Android TV interface | Separate product/UI; out of scope for a phone patch |
| Search, browse, library and playlist redesign | App-wide UI/data architecture; out of scope |

## Existing work in this branch

- BitChordAudioEngine.kt: independent onset-envelope BPM estimator, conservative beat transition planner, biquad EQ primitive and multichannel echo-out primitive.
- BitChordAudioEngineSelfTest.kt: framework-free DSP smoke tests.
- CI workflow: Morphe patch bundle build, DSP smoke tests and patch metadata generation.
- Existing Force Opus patch remains separate and opt-in.

The above are foundations only. They do not become active in YouTube Music until hooked to the exact target APK's playback and audio pipeline.

## Required integration inputs and release gates

1. Exact YouTube Music APK used by the target device, with package/version/build fingerprint. The existing Force Opus patch is pinned to 9.42.52; the new hooks must be verified against the same exact APK, not assumed compatible from the version string alone.
2. Map the actual YTM player construction, audio sink, queue advance, media-session handoff and audio-focus lifecycle.
3. Integrate one feature at a time, starting with crossfade, then analysis/Automix, then EQ/filter/echo, then network quality policy.
4. External source modules must be opt-in, allow-listed, isolated, time-bounded and transparent. Do not intercept DRM-protected streams, extract session tokens, impersonate authenticated requests or bypass subscription/entitlement checks.
5. Validate playback, pause, seek, skip, queue edits, repeat/shuffle, background playback, Bluetooth, wired/USB output, Cast, Android Auto, video mode, downloads and long sessions.
6. Verify the patch applies to the APK, inspect the resulting DEX, and run on-device audio tests before calling any feature functional.
7. Do not publish a stable release while a feature is only represented by a helper class or compile-time test.

## Current status

The first CI run for this branch completed the Morphe patch-bundle build, DSP smoke-test step, metadata generation and artifact upload successfully. This proves the branch builds and its standalone DSP checks run. It does not prove any new DSP feature is wired into the YouTube Music runtime or that an APK has been patched and tested.