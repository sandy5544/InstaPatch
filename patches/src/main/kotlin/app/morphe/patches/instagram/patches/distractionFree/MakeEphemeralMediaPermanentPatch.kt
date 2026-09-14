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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Instagram 439 stores ephemeral DM media in a dedicated object parsed from
 * JSON. The proven Piko fix hooks the parser's conditional branch, reads the
 * object's expiration and view mode, rewrites the same object, then returns it.
 * Hooking only at RETURN_OBJECT is insufficient because later parser logic can
 * replace the value or construct another instance.
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
    description = "Makes Instagram view once and view twice DM media permanently replayable.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    extendWith("extensions/instagram.mpe")

    execute {
        EphemeralMediaJsonParserFingerprint.apply {
            val viewModeStringIndex = stringMatches[1].index
            method.apply {
                val viewModePut = getInstruction(indexOfFirstInstruction(viewModeStringIndex, Opcode.IPUT_OBJECT))
                val viewModeField = (viewModePut as ReferenceInstruction).reference as FieldReference
                val mediaClass = viewModeField.definingClass
                val viewModeFieldName = viewModeField.name

                val expireAtField = instructions
                    .filter { it.location.index < viewModeStringIndex && it.opcode == Opcode.IPUT_OBJECT }
                    .last()
                    .let { (it as ReferenceInstruction).reference as FieldReference }

                val returnObject = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val mediaRegister = returnObject.registersUsed[0]

                val ifEqInstructions = instructions.filter { it.opcode == Opcode.IF_EQ }
                check(ifEqInstructions.size >= 2) { "Instagram 439: expected ephemeral-media IF_EQ branch" }
                val branch = ifEqInstructions[1]
                val branchRegisters = branch.registersUsed
                check(branchRegisters.size >= 2) { "Instagram 439: invalid ephemeral-media IF_EQ registers" }

                addInstructionsWithLabels(
                    branch.location.index,
                    """
                    if-ne v${branchRegisters[0]}, v${branchRegisters[1]}, :morphe_ephemeral_continue

                    iget-object v0, v$mediaRegister, $mediaClass->${expireAtField.name}:${expireAtField.type}
                    iget-object v1, v$mediaRegister, $mediaClass->$viewModeFieldName:${viewModeField.type}
                    invoke-static {v0, v1}, Lapp/morphe/extension/instagram/EphemeralMediaPatch;->makePermanent(Ljava/lang/Long;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1
                    iput-object v1, v$mediaRegister, $mediaClass->$viewModeFieldName:${viewModeField.type}
                    return-object v$mediaRegister
                    """.trimIndent(),
                    ExternalLabel("morphe_ephemeral_continue", branch),
                )
            }
        }
    }
}
