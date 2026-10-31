package app.morphe.patches.youtube.music.audio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val SELECTABLE_FORMATS =
    "Lcom/google/android/apps/youtube/proto/streaming/SelectableFormatsOuterClass\$SelectableFormats;"

private object AudioFormatSelectionFingerprint : Fingerprint(
    definingClass = "Lyop;",
    name = "n",
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
    returnType = "Lyop;",
    custom = { methodDef, _ -> AccessFlags.STATIC.isSet(methodDef.accessFlags) }
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
    default = false
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

        // Lyon.b is the first wrapper in the group and is also used by
        // Lyon.compareTo() for stable group ordering. It is NOT the current
        // best candidate after later quality replacements. Track the selected
        // codec separately instead of reading or mutating Lyon.b.
        val lyonClass = mutableClassDefBy("Lyon;")
        check(lyonClass.fields.none { it.name == "patch_codec" }) {
            "Force Opus state field already exists; refusing duplicate injection."
        }
        lyonClass.fields.add(
            ImmutableField(
                "Lyon;",
                "patch_codec",
                "Lwgr;",
                0,
                null,
                null,
                null
            ).toMutable()
        )

        val lyonConstructor = lyonClass.methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.map { type -> type.toString() } == listOf("Lwgs;")
        } ?: error("Lyon(Lwgs;) constructor not found or ambiguous.")
        check(lyonConstructor.implementation?.registerCount == 4) {
            "Lyon constructor register layout changed."
        }
        val constructorCode = lyonConstructor.instructions.toList()
        val wrapperStoreIndices = constructorCode.indices.filter { index ->
            val field = (constructorCode[index] as? ReferenceInstruction)?.reference as? FieldReference
            constructorCode[index].opcode == Opcode.IPUT_OBJECT &&
                field?.definingClass == "Lyon;" &&
                field.name == "b" &&
                field.type == "Lwgs;"
        }
        check(wrapperStoreIndices.size == 1) {
            "Expected one Lyon.b constructor assignment, found ${wrapperStoreIndices.size}."
        }
        lyonConstructor.addInstructionsWithLabels(
            wrapperStoreIndices.single() + 1,
            """
                invoke-virtual { p1 }, Lwgs;->z()Ljava/lang/String;
                move-result-object v0
                invoke-static { v0 }, Lwgs;->p(Ljava/lang/String;)Lwgr;
                move-result-object v0
                iput-object v0, p0, Lyon;->patch_codec:Lwgr;
            """
        )

        val lwgsClass = classDefBy("Lwgs;")
        val codecStringAccessor = lwgsClass.methods.singleOrNull {
            it.name == "z" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } ?: error("Lwgs codec-string accessor not found or ambiguous.")
        check(!AccessFlags.STATIC.isSet(codecStringAccessor.accessFlags)) {
            "Lwgs codec-string accessor is no longer an instance method."
        }
        val codecClassifier = lwgsClass.methods.singleOrNull {
            it.name == "p" &&
                it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/String;") &&
                it.returnType == "Lwgr;"
        } ?: error("Lwgs codec classifier not found or ambiguous.")
        check(AccessFlags.STATIC.isSet(codecClassifier.accessFlags)) {
            "Lwgs codec classifier is no longer static."
        }
        val opusEnumField = classDefBy("Lwgr;").fields.singleOrNull {
            it.name == "b" && it.type == "Lwgr;"
        } ?: error("Opus enum constant Lwgr.b not found or ambiguous.")
        check(AccessFlags.STATIC.isSet(opusEnumField.accessFlags)) {
            "Lwgr.b is no longer a static enum constant."
        }

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
        check(temp + 2 < registerCount) {
            "Insufficient existing local registers for the guarded Opus comparison."
        }
        check(
            registerCount == 35 &&
                temp == 12 &&
                candidateWrapperRegister == 7 &&
                currentGroupRegister == 10 &&
                resultRegister == 11
        ) {
            "Lyop.n register layout differs from the inspected YouTube Music 9.42.52 APK."
        }

        // v15 is live later in Lyop.n. v13 and v14 are reused only after their
        // original values have become dead; fail closed if either register is
        // read before the original method first overwrites it.
        fun touchesRegister(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction, register: Int): Boolean =
            when (instruction) {
                is FiveRegisterInstruction -> listOf(
                    instruction.registerC, instruction.registerD, instruction.registerE,
                    instruction.registerF, instruction.registerG
                ).take(instruction.registerCount).contains(register)
                is RegisterRangeInstruction ->
                    register >= instruction.startRegister &&
                        register < instruction.startRegister + instruction.registerCount
                is ThreeRegisterInstruction ->
                    instruction.registerA == register ||
                        instruction.registerB == register ||
                        instruction.registerC == register
                is TwoRegisterInstruction ->
                    instruction.registerA == register || instruction.registerB == register
                is OneRegisterInstruction -> instruction.registerA == register
                else -> false
            }

        for (register in (temp + 1)..(temp + 2)) {
            val firstTouchIndex = code.indices.firstOrNull { index ->
                index >= branchIndex && touchesRegister(code[index], register)
            } ?: error("Temporary register v$register is never redefined after the selection branch.")
            val firstTouch = code[firstTouchIndex]
            check(
                firstTouch.opcode == Opcode.MOVE_OBJECT &&
                    (firstTouch as? TwoRegisterInstruction)?.registerA == register
            ) {
                "Temporary register v$register is read or reused before its original value is overwritten."
            }
        }

        val currentWrapperOrCodec = temp
        val codecOrEnum = temp + 1
        val opusEnum = temp + 2

        method.addInstructionsWithLabels(
            branchIndex,
            """
                iget-object v$currentWrapperOrCodec, v$currentGroupRegister, Lyon;->patch_codec:Lwgr;
                invoke-virtual { v$candidateWrapperRegister }, Lwgs;->z()Ljava/lang/String;
                move-result-object v$codecOrEnum
                invoke-static { v$codecOrEnum }, Lwgs;->p(Ljava/lang/String;)Lwgr;
                move-result-object v$codecOrEnum
                sget-object v$opusEnum, Lwgr;->b:Lwgr;
                if-ne v$codecOrEnum, v$opusEnum, :force_opus_candidate_not_opus
                if-ne v$currentWrapperOrCodec, v$opusEnum, :force_opus_candidate_wins
                goto :force_opus_done
                :force_opus_candidate_wins
                iput-object v$codecOrEnum, v$currentGroupRegister, Lyon;->patch_codec:Lwgr;
                const/4 v$resultRegister, 0x1
                goto :force_opus_done
                :force_opus_candidate_not_opus
                if-ne v$currentWrapperOrCodec, v$opusEnum, :force_opus_done
                const/4 v$resultRegister, -0x1
                :force_opus_done
                nop
            """
        )
    }
}
