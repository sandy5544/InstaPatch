# Force Opus bytecode integration checklist

This is the implementation checklist for the Morphe bytecode hook. It is deliberately strict because this patch changes media selection.

## Required hook point

Target method: `Lyop.n(...)` in YouTube Music 9.42.52.

The hook must be inside the audio-format path sourced from `SelectableFormats.c`, after audio wrappers have been created and while the existing per-group candidate is being compared with a new candidate.

## Required runtime decision

For a candidate and the currently retained candidate in the same group:

| Candidate | Current | Decision |
|---|---|---|
| Opus | AAC/other | Replace with candidate |
| AAC/other | Opus | Keep current |
| Opus | Opus | Use original quality comparison |
| AAC/other | AAC/other | Use original quality comparison |

Do not change the grouping key. Do not change the global codec classifier. Do not alter the video list.

## Structural guards before injection

The bytecode patch must fail with a useful fingerprint error unless all of these are uniquely identified:

- The exact `Lyop.n` method for the supported app version.
- The audio list path, not the video list path.
- The candidate `Lwgs` wrapper register.
- The retained `Lwgs` wrapper register from the existing group entry.
- The quality ordinal register used by the original replacement comparison.
- The branch target that keeps the current candidate.

The patch must reserve local registers safely and preserve all existing control-flow targets.

## Validation matrix

- Opus and AAC in one group, Opus wins.
- Two Opus candidates, higher quality ordinal wins.
- No Opus candidate, original fallback remains.
- Multiple language groups remain independent.
- Commentary groups remain independent.
- Video candidates and video quality remain unchanged.
- Audio track selection and actual playback succeed on-device.

Structural guards are implemented in `ForceOpusAudioPatch.kt`. The patch is registered in source but must not be released as stable until the Morphe build and runtime matrix pass.
