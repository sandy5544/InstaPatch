package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Instagram's ephemeral-media parser contains the fields used for view-once /
 * view-twice media. Normalize the parsed view mode at the same branch used by
 * Piko, before the parser can return the ephemeral object.
 */
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
    description = "Makes Instagram view once and view twice DM media permanently replayable, including after leaving and returning to the chat.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        EphemeralMediaJsonParserFingerprint.apply {
            method.apply {
                val viewModeStringIndex = stringMatches[1].index
                val viewModePut = instructions.first {
                    it.location.index > viewModeStringIndex && it.opcode == Opcode.IPUT_OBJECT
                }
                val viewModeField = (viewModePut as ReferenceInstruction).reference as FieldReference
                val mediaClass = viewModeField.definingClass
                val viewModeFieldName = viewModeField.name

                val returnObject = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val mediaRegister = (returnObject as OneRegisterInstruction).registerA

                val ifEqInstructions = instructions.filter { it.opcode == Opcode.IF_EQ }
                check(ifEqInstructions.size >= 2) { "Instagram 439: expected ephemeral-media IF_EQ branch" }
                val branch = ifEqInstructions[1]
                val branchRegisters = branch as TwoRegisterInstruction
                val registerA = branchRegisters.registerA
                val registerB = branchRegisters.registerB

                addInstructionsWithLabels(
                    branch.location.index,
                    """
                    if-ne v$registerA, v$registerB, :morphe_ephemeral_continue
                    const-string v1, "permanent"
                    iput-object v1, v$mediaRegister, $mediaClass->$viewModeFieldName:${viewModeField.type}
                    return-object v$mediaRegister
                    """.trimIndent(),
                    ExternalLabel("morphe_ephemeral_continue", branch),
                )
            }
        }
    }
}
