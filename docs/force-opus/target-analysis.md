# YouTube Music 9.42.52 bytecode anchors

Target APK SHA-256:

`9c38d63907362715f6172545bcb36f11ddbd45f014938626068016f06dcc3c56`

DEX file: `classes4.dex`

## Selection method

`Lyop.n(Lyoc;, Lwiu;, Laxo;, List, List, SelectableFormats, MediaCapabilities, Z, Z, Z, Z, Z, Z, Z):Lyop;`

The method has 35 registers, 14 incoming register words, 19 outgoing register words, and 1,454 code units in the inspected APK. The code item was parsed directly from `classes4.dex`; the offsets below are code-unit offsets, not instruction-list indices.

## Audio-only path

The method reads the two protobuf lists separately:

- `SelectableFormats.b:Laiie;` is the video list.
- `SelectableFormats.c:Laiie;` is the audio list.

The Opus preference hook is after the audio-list read and after the audio candidates have been wrapped as `Lwgs`.

## Candidate replacement block

DEX code-unit offsets in the unmodified APK:

| Offset | Operation |
|---|---|
| `0x026c` | Read the existing group key using `Lwgs.A()` |
| `0x0289` | Read candidate quality using `Lwgs.q()` |
| `0x028d` | Read retained quality from `Lyon.c:Laipc;` |
| `0x028f` | Compare candidate and retained quality using `Laipc.compareTo(Enum):I` |
| `0x0293` | `if-lez` skips replacement when candidate quality is not higher |
| `0x0295` | Re-read candidate quality for the replacement path |

At the comparison site in the inspected build:

- Candidate `Lwgs` wrapper is in `v7`.
- The retained `Lyon` group object is in `v10`.
- Candidate quality is returned in `v11`.
- Retained quality is read into `v12`.
- `compareTo` returns its integer result in `v11`.

A deeper register-liveness pass found that `v15` is used later in the same method (at code-unit offset `0x02eb`). An earlier draft of the hook reused `v15`, which would have corrupted a later operation. That register is now deliberately excluded.

The hook uses only `v12-v14`. The original value in `v12` is consumed by `compareTo`; the first subsequent uses of `v13` and `v14` in the original instruction stream are writes at `0x05a1` and `0x05a2`, respectively. The source checks the first-touch instruction for those registers and fails closed if either is read before being overwritten. It also asserts the target method's register count and key register assignments.

## Injected decision and retained-codec state

A further inspection of `Lyon.<init>` and `Lyon.compareTo` exposed an important distinction: `Lyon.b` stores the first wrapper for the group and is used for stable sorting. It is not updated when the quality winner changes. It must therefore not be used as the current codec.

The patch adds a package-private `Lyon.patch_codec:Lwgr;` state field:

1. The `Lyon(Lwgs)` constructor initializes it from the first candidate's codec.
2. When an Opus candidate replaces a non-Opus candidate, the hook updates the state to Opus.
3. A later non-Opus candidate is rejected while the retained codec state is Opus.
4. When both candidates are Opus or both are non-Opus, the original quality comparison remains authoritative.

This keeps `Lyon.b` unchanged, preserving the sort behavior in `Lyon.compareTo`.

Codec identification reuses `Lwgs.z()`, `Lwgs.p(String)`, and `Lwgr.b` (the Opus enum constant). Direct DEX access-flag inspection confirms `z()` is an instance method, `p(String)` is static, and `Lwgr.b` is a static enum field. `Lyon.patch_codec` is package-private, and both `Lyop` and `Lyon` are in the default DEX package. The patch does not modify `Lwgs.p()` itself.

## Validation limits

The selection block, register assignments, relevant access flags, and temporary-register liveness were checked against the original APK's raw DEX code item. The Morphe patch module compiled and the generated `.mpp` bundle included the patch metadata before the latest state-tracking changes. A fresh CI build is required for the current commit. The patch is opt-in by default while validation remains incomplete.

Still outstanding: applying this patch bundle to the exact APK, verifying the resulting DEX with the Morphe patcher, and testing actual playback. Compilation and patch-list generation do not prove that the injected smali assembles or that playback selects Opus. The source guards intentionally abort if the inspected method shape differs.
