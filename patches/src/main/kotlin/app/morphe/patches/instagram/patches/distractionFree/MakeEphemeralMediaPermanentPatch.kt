package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

private object EphemeralMediaJsonParserFingerprint : Fingerprint(
    custom = { methodDef, _ ->
        methodDef.name.lowercase().contains("parsefromjson")
    },
    returnType = "Ljava/lang/Object;",
    strings = listOf("url_expire_at_secs", "view_mode", "seen_count", "tap_models"),
)

@Suppress("unused")
val antiViewOnceMediaPatch = bytecodePatch(
    name = "Anti View Once Media",
    description = "Makes unexpired Instagram view once and view twice DM media permanently replayable.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        EphemeralMediaJsonParserFingerprint.apply {
            val expireAtStringIndex = stringMatches[0].index
            val viewModeStringIndex = stringMatches[1].index

            method.apply {
                val viewModePut = getInstruction(
                    indexOfFirstInstruction(viewModeStringIndex, Opcode.IPUT_OBJECT),
                )
                val viewModeField = viewModePut.fieldExtractor()
                val mediaClass = viewModeField.definingClass
                val viewModeFieldName = viewModeField.name

                val expireAtField = instructions.last {
                    it.location.index < viewModeStringIndex && it.opcode == Opcode.IPUT_OBJECT &&
                        it.location.index >= expireAtStringIndex
                }.fieldExtractor()
                val expireAtFieldName = expireAtField.name

                val returnObject = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val mediaRegister = returnObject.registersUsed[0]

                val branch = instructions.filter { it.opcode == Opcode.IF_EQ }[1]
                val branchRegisters = branch.registersUsed
                val registerA = branchRegisters[0]
                val registerB = branchRegisters[1]

                addInstructionsWithLabels(
                    branch.location.index,
                    """
                    if-ne v$registerA, v$registerB, :morphe_ephemeral_continue
                    iget-object v0, v$mediaRegister, $mediaClass->$expireAtFieldName:Ljava/lang/Long;
                    iget-object v1, v$mediaRegister, $mediaClass->$viewModeFieldName:Ljava/lang/String;
                    invoke-static {v0, v1}, Lapp/morphe/extension/instagram/patches/dm/EphemeralMediaPatch;->makeEphemeralMediaPermanent(Ljava/lang/Long;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    iput-object v1, v$mediaRegister, $mediaClass->$viewModeFieldName:Ljava/lang/String;
                    return-object v$mediaRegister
                    """.trimIndent(),
                    ExternalLabel("morphe_ephemeral_continue", branch),
                )
            }
        }
    }
}
