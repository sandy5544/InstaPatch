package app.morphe.patches.youtube.music.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SELECTABLE_FORMATS =
    "Lcom/google/android/apps/youtube/proto/streaming/SelectableFormatsOuterClass\$SelectableFormats;"

private object AudioFormatSelectionFingerprint : Fingerprint(
    definingClass = "Lyop;",
    name = "n",
    isStatic = true,
    parameters = listOf(
        "Lyoc;",
        "Lwiu;",
        "Laxo;",
        "Ljava/util/List;",
        "Ljava/util/List;",
        SELECTABLE_FORMATS,
        "Lcom/google/android/apps/youtube/proto/streaming/MediaCapabilitiesOuterClass\$MediaCapabilities;",
        "Z", "Z", "Z", "Z", "Z", "Z", "Z"
    ),
    returnType = "Lyop;"
)

/**
 * Prefers Opus only while replacing the selected audio candidate for an
 * existing group. The original Laipc quality comparison remains authoritative
 * when both candidates are Opus or both are non-Opus.
 *
 * This is intentionally pinned to YouTube Music 9.42.52. Structural checks
 * make the patch fail closed if the obfuscated method layout changes.
 */
@Suppress("unused")
val forceOpusAudioPatch = bytecodePatch(
    name = "Force Opus audio codec",
    description = "Prefers Opus audio within each existing audio group while preserving quality and language fallbacks.",
    default = true
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val method = AudioFormatSelectionFingerprint.method
        val code = method.instructions.toList()

        fun methodReference(index: Int): MethodReference? =
            (code[index] as? ReferenceInstruction)?.reference as? MethodReference

        fun fieldReference(index: Int): FieldReference? =
            (code[index] as? ReferenceInstruction)?.reference as? FieldReference

        // Prove this is the audio-list path, not the earlier video-list path.
        val videoListIndex = code.indices.firstOrNull { index ->
            val field = fieldReference(index)
            field?.definingClass == SELECTABLE_FORMATS &&
                field.name == "b" && field.type == "Laiie;"
        } ?: error("YouTube Music video format list anchor not found.")

        val audioListIndex = code.indices.firstOrNull { index ->
            val field = fieldReference(index)
            field?.definingClass == SELECTABLE_FORMATS &&
                field.name == "c" && field.type == "Laiie;"
        } ?: error("YouTube Music audio format list anchor not found.")

        check(videoListIndex < audioListIndex) {
            "SelectableFormats video/audio list order changed; refusing to patch."
        }

        val compareMatches = code.indices.filter { index ->
            val ref = methodReference(index)
            ref?.definingClass == "Laipc;" &&
                ref.name == "compareTo" &&
                ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Enum;") &&
                ref.returnType == "I"
        }
        check(compareMatches.size == 1) {
            "Expected one audio quality comparison in Lyop.n, found ${compareMatches.size}."
        }
        val compareIndex = compareMatches.single()
        check(compareIndex > audioListIndex) {
            "Quality comparison is not downstream of SelectableFormats audio list."
        }

        val compareInstruction = code[compareIndex] as? FiveRegisterInstruction
            ?: error("Unexpected Laipc.compareTo instruction format.")
        val candidateQualityResult = code.getOrNull(compareIndex - 2)
        check(candidateQualityResult?.opcode == Opcode.MOVE_RESULT_OBJECT) {
            "Candidate quality result anchor changed."
        }
        val candidateQualityRegister =
            (candidateQualityResult as OneRegisterInstruction).registerA
        check(compareInstruction.registerC == candidateQualityRegister) {
            "Candidate quality register no longer matches the original compare."
        }

        val candidateQualityCallIndex = (0 until compareIndex).lastOrNull { index ->
            val ref = methodReference(index)
            ref?.definingClass == "Lwgs;" &&
                ref.name == "q" &&
                ref.parameterTypes.isEmpty() &&
                ref.returnType == "Laipc;"
        } ?: error("Candidate Lwgs.q() call not found.")

        val candidateQualityCall = code[candidateQualityCallIndex] as? FiveRegisterInstruction
            ?: error("Unexpected candidate Lwgs.q() instruction format.")
        val candidateWrapperRegister = candidateQualityCall.registerC

        val currentQualityReadIndex = (candidateQualityCallIndex + 1 until compareIndex).lastOrNull { index ->
            val field = fieldReference(index)
            code[index].opcode == Opcode.IGET_OBJECT &&
                field?.definingClass == "Lyon;" &&
                field.name == "c" &&
                field.type == "Laipc;"
        } ?: error("Stored Lyon quality field read not found.")

        val currentQualityRead = code[currentQualityReadIndex] as TwoRegisterInstruction
        check(compareInstruction.registerD == currentQualityRead.registerA) {
            "Stored quality register no longer matches the original compare."
        }
        val currentGroupRegister = currentQualityRead.registerB

        // The selected wrapper is Lyon.b. Verify the exact field before emitting
        // the injected instructions rather than assuming the obfuscation mapping.
        val lyonClass = classDefBy("Lyon;")
        check(lyonClass.fields.any {
            it.name == "b" && it.type == "Lwgs;"
        }) { "Selected Lyon audio-wrapper field not found." }

        val lwgsClass = classDefBy("Lwgs;")
        check(lwgsClass.methods.any {
            it.name == "z" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        }) { "Lwgs codec-string accessor not found." }
        check(lwgsClass.methods.any {
            it.name == "p" &&
                it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/String;") &&
                it.returnType == "Lwgr;"
        }) { "Lwgs codec classifier not found." }
        check(classDefBy("Lwgr;").fields.any {
            it.name == "b" && it.type == "Lwgr;"
        }) { "Opus enum constant Lwgr.b not found." }

        val resultMoveIndex = compareIndex + 1
        val branchIndex = compareIndex + 2
        check(code.getOrNull(resultMoveIndex)?.opcode == Opcode.MOVE_RESULT) {
            "Quality comparison result move changed."
        }
        check(code.getOrNull(branchIndex)?.opcode == Opcode.IF_LEZ) {
            "Quality replacement branch changed; refusing to patch."
        }

        val resultRegister =
            (code[resultMoveIndex] as OneRegisterInstruction).registerA
        val branchRegister =
            (code[branchIndex] as OneRegisterInstruction).registerA
        check(resultRegister == branchRegister) {
            "Quality branch no longer consumes the comparison result."
        }

        // The current-quality destination is dead after compareTo. Reuse it and
        // the following local registers, avoiding any change to parameter layout.
        val temp = currentQualityRead.registerA
        val registerCount = method.implementation?.registerCount
            ?: error("Lyop.n has no implementation.")
        check(temp + 3 < registerCount) {
            "Insufficient existing local registers for the guarded Opus comparison."
        }
        check(temp > maxOf(candidateWrapperRegister, currentGroupRegister, resultRegister)) {
            "Unexpected register overlap at the audio selection site."
        }

        val currentWrapper = temp
        val codecOrEnum = temp + 1
        val opusEnum = temp + 2
        val currentCodecOrEnum = temp + 3

        method.addInstructions(
            branchIndex,
            """
                iget-object v$currentWrapper, v$currentGroupRegister, Lyon;->b:Lwgs;
                invoke-virtual { v$candidateWrapperRegister }, Lwgs;->z()Ljava/lang/String;
                move-result-object v$codecOrEnum
                invoke-static { v$codecOrEnum }, Lwgs;->p(Ljava/lang/String;)Lwgr;
                move-result-object v$codecOrEnum
                sget-object v$opusEnum, Lwgr;->b:Lwgr;
                if-ne v$codecOrEnum, v$opusEnum, :force_opus_candidate_not_opus
                invoke-virtual { v$currentWrapper }, Lwgs;->z()Ljava/lang/String;
                move-result-object v$currentCodecOrEnum
                invoke-static { v$currentCodecOrEnum }, Lwgs;->p(Ljava/lang/String;)Lwgr;
                move-result-object v$currentCodecOrEnum
                if-ne v$currentCodecOrEnum, v$opusEnum, :force_opus_candidate_wins
                goto :force_opus_done
                :force_opus_candidate_wins
                const/4 v$resultRegister, 0x1
                goto :force_opus_done
                :force_opus_candidate_not_opus
                invoke-virtual { v$currentWrapper }, Lwgs;->z()Ljava/lang/String;
                move-result-object v$currentCodecOrEnum
                invoke-static { v$currentCodecOrEnum }, Lwgs;->p(Ljava/lang/String;)Lwgr;
                move-result-object v$currentCodecOrEnum
                if-ne v$currentCodecOrEnum, v$opusEnum, :force_opus_done
                const/4 v$resultRegister, -0x1
                :force_opus_done
                nop
            """
        )
    }
}
