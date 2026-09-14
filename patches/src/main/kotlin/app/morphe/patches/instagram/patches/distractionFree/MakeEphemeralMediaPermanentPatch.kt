package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Instagram's ephemeral-media parser contains the fields used for view-once /
 * view-twice media. Normalize the parsed view mode before the object is
 * returned so subsequent reparses do not restore the ephemeral state.
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

                // Instagram 439 has two IF_EQ instructions in this parser. The
                // second one is the ephemeral-media branch used by Piko.
                val ifEqInstructions = instructions.filter { it.opcode == Opcode.IF_EQ }
                check(ifEqInstructions.size >= 2) { "Instagram 439: expected ephemeral-media IF_EQ branch" }
                val branch = ifEqInstructions[1]
                val branchRegisters = (branch as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction)
                val registerA = branchRegisters.registerA
                val registerB = branchRegisters.registerB

                addInstructions(
                    branch.location.index,
                    """
                    if-ne v$registerA, v$registerB, :morphe_ephemeral_continue
                    const-string v1, "permanent"
                    iput-object v1, v$mediaRegister, $mediaClass->$viewModeFieldName:${viewModeField.type}
                    return-object v$mediaRegister
                    """.trimIndent(),
                )
            }
        }
    }
}
