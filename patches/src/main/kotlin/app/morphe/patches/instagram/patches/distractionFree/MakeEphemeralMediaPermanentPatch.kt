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
 * view-twice media. Piko's implementation only converts the object while its
 * expiration timestamp is still in the future. That leaves the same media
 * ephemeral again after Instagram reparses it later, for example after leaving
 * and returning to the conversation.
 *
 * We deliberately remove that time dependency: once this parser has identified
 * an ephemeral-media object, its view_mode is always normalized to "permanent".
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
                val viewModeField =
                    (viewModePut as ReferenceInstruction).reference as FieldReference
                val ephemeralMediaClassName = viewModeField.definingClass
                val returnObject = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val objectRegister = (returnObject as OneRegisterInstruction).registerA
                val scratchRegister = if (objectRegister == 0) 1 else 0

                addInstructions(
                    returnObject.location.index,
                    """
                        const-string v$scratchRegister, "permanent"
                        iput-object v$scratchRegister, v$objectRegister, $ephemeralMediaClassName->${viewModeField.name}:Ljava/lang/String;
                    """.trimIndent(),
                )
            }
        }
    }
}
