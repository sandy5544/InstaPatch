package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patches.instagram.entity.messageInfoEntity.messageInfoEntity
import app.morphe.patches.instagram.misc.directMessage.saveAllMessages.saveAllMessagesPatch
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patches.instagram.utils.enableSettings
import app.morphe.util.extensionToClassName
import app.morphe.util.fieldExtractor
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

internal object EphemeralMediaJsonParserFingerprint : Fingerprint(
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
    dependsOn(settingsPatch, messageInfoEntity, saveAllMessagesPatch)

    execute {
        EphemeralMediaJsonParserFingerprint.apply {
            val expireAtStringIndex = stringMatches[0].index
            val viewModeStringIndex = stringMatches[1].index
            method.apply {
                val viewModeIPutObjectInstruction =
                    getInstruction(indexOfFirstInstruction(viewModeStringIndex, Opcode.IPUT_OBJECT))

                val viewModeInstructionExtraction = viewModeIPutObjectInstruction.fieldExtractor()
                val ephemeralMediaClassName = extensionToClassName(viewModeInstructionExtraction.definingClass)
                val viewModeFieldName = viewModeInstructionExtraction.name

                val expireAtInstructionExtraction =
                    instructions.last {
                        it.location.index < viewModeStringIndex && it.opcode == Opcode.IPUT_OBJECT
                    }.fieldExtractor()
                val expireAtFieldName = expireAtInstructionExtraction.name

                val returnObjectInstruction = instructions.last { it.opcode == Opcode.RETURN_OBJECT }
                val ephemeralMediaClassRegister = returnObjectInstruction.registersUsed[0]

                val midIfEqInstruction = instructions.filter { it.opcode == Opcode.IF_EQ }[1]
                val midIfEqIndex = midIfEqInstruction.location.index
                val registers = midIfEqInstruction.registersUsed
                val registerA = registers[0]
                val registerB = registers[1]

                addInstructionsWithLabels(
                    midIfEqIndex,
                    """
                    if-ne v$registerA, v$registerB, :piko_continue

                    iget-object v0, v$ephemeralMediaClassRegister, $ephemeralMediaClassName->$expireAtFieldName:Ljava/lang/Long;
                    iget-object v1, v$ephemeralMediaClassRegister, $ephemeralMediaClassName->$viewModeFieldName:Ljava/lang/String;

                    invoke-static {v0, v1}, $PATCHES_DESCRIPTOR/dm/EphemeralMediaPatch;->makeEphemeralMediaPermanent(Ljava/lang/Long;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v1

                    iput-object v1, v$ephemeralMediaClassRegister, $ephemeralMediaClassName->$viewModeFieldName:Ljava/lang/String;
                    return-object v$ephemeralMediaClassRegister
                    """.trimIndent(),
                    ExternalLabel("piko_continue", midIfEqInstruction),
                )
            }
        }
        enableSettings("unlimitedReplaysOnEphemeralMedia")
    }
}
