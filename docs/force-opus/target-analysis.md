# YouTube Music 9.42.52 bytecode anchors

Target APK SHA-256:

`9c38d63907362715f6172545bcb36f11ddbd45f014938626068016f06dcc3c56`

DEX file: `classes4.dex`

## Selection method

`Lyop.n(Lyoc;, Lwiu;, Laxo;, List, List, SelectableFormats, MediaCapabilities, Z, Z, Z, Z, Z, Z, Z):Lyop;`

The method has 35 registers and 14 incoming register words in the inspected APK.

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
- The comparison result is in `v11`.
- The retained quality read is in `v12`.

The patch derives these registers from the instruction references and validates the compare/result/branch sequence before injection. It reuses `v12-v15` as temporaries. The quality result register is overridden only when candidate and retained codecs differ in Opus status.

## Injected decision

- Candidate Opus, retained non-Opus: set compare result to positive, allowing replacement.
- Candidate non-Opus, retained Opus: set compare result to negative, preserving the retained candidate.
- Both Opus or both non-Opus: leave the original compare result untouched.

Codec identification reuses `Lwgs.z()`, `Lwgs.p(String)`, and `Lwgr.b` (the Opus enum constant). The patch does not modify `Lwgs.p()` itself.

## Validation limits

These anchors were statically inspected in the target APK. The Morphe patch must still compile, apply to the APK, pass DEX verification, and be tested during actual playback. The source guards intentionally abort if the target method shape differs.
