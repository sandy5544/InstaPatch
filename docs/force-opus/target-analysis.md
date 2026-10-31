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

The current hook uses only `v12-v14`. The original value in `v12` is consumed by `compareTo`; the first subsequent uses of `v13` and `v14` in the original instruction stream are writes at `0x05a1` and `0x05a2`, respectively. The source now checks the first-touch instruction for those registers and fails closed if either is read before being overwritten. It also asserts the target method's register count and key register assignments.

## Injected decision

- Candidate Opus, retained non-Opus: set compare result to positive, allowing replacement.
- Candidate non-Opus, retained Opus: set compare result to negative, preserving the retained candidate.
- Both Opus or both non-Opus: leave the original compare result untouched.

Codec identification reuses `Lwgs.z()`, `Lwgs.p(String)`, and `Lwgr.b` (the Opus enum constant). Direct DEX access-flag inspection confirms `z()` is an instance method, `p(String)` is static, and `Lwgr.b` is a static enum field. `Lyon.b` is a package-private instance field, and both `Lyop` and `Lyon` are in the default DEX package. The hook therefore reads it without changing field visibility. The patch does not modify `Lwgs.p()` itself.

## Validation limits

The selection block, register assignments, relevant access flags, and temporary-register liveness were checked against the original APK's raw DEX code item. The Morphe patch module compiles and the generated `.mpp` bundle includes the patch metadata.

Still outstanding: applying this patch bundle to the exact APK, verifying the resulting DEX with the Morphe patcher, and testing actual playback. Compilation and patch-list generation do not prove that the injected smali assembles or that playback selects Opus. The source guards intentionally abort if the inspected method shape differs.
