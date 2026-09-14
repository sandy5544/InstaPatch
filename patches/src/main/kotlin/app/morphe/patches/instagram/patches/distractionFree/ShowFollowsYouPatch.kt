package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private object ProfileUserInfoViewBinderFingerprint : Fingerprint(
    strings = listOf("ProfileUserInfoViewBinder.newView"),
)

private object BindInternalBadgeFingerprint : Fingerprint(
    strings = listOf("bindInternalBadges"),
)

@Suppress("unused")
val showFollowsYouPatch = bytecodePatch(
    name = "Show Follows You",
    description = "Shows a Follows you indicator on Instagram profiles for accounts that follow you.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    extendWith("extensions/instagram.mpe")

    execute {
        BindInternalBadgeFingerprint.apply {
            val isStaticMethod = AccessFlags.STATIC.isSet(method.accessFlags)
            method.apply {
                val viewType = "Landroid/view/View;"
                val profileInfoClassType = ProfileUserInfoViewBinderFingerprint.method.parameters[1].type

                var profileInfoParameter = parameters.indexOfFirst { it.type == profileInfoClassType }
                var viewParameter = parameters.indexOfFirst { it.type == viewType }
                check(profileInfoParameter >= 0) { "Instagram 439: profile info parameter not found in bindInternalBadges" }
                check(viewParameter >= 0) { "Instagram 439: View parameter not found in bindInternalBadges" }

                if (!isStaticMethod) {
                    profileInfoParameter += 1
                    viewParameter += 1
                }

                val badgeStringIndex = BindInternalBadgeFingerprint.stringMatches[0].index
                val badgeGetInstruction = instructions.first {
                    it.location.index > badgeStringIndex && it.opcode == Opcode.IGET_OBJECT
                }
                val badgeField = (badgeGetInstruction as ReferenceInstruction).reference as FieldReference
                val badgeClass = badgeField.definingClass
                val badgeFieldName = badgeField.name
                val badgeFieldType = badgeField.type

                val continueInstruction = instructions.first {
                    it.location.index > badgeGetInstruction.location.index && it.opcode == Opcode.MOVE_FROM16
                }

                addInstructionsWithLabels(
                    badgeGetInstruction.location.index + 1,
                    """
                    goto :morphe_follows_continue
                    """.trimIndent(),
                    ExternalLabel("morphe_follows_continue", continueInstruction),
                )

                addInstructionsWithLabels(
                    0,
                    """
                    invoke-virtual/range {p$viewParameter .. p$viewParameter}, $viewType->getTag()Ljava/lang/Object;
                    move-result-object v1
                    if-eqz v1, :morphe_follows_done
                    check-cast v1, $badgeClass
                    iget-object v2, v1, $badgeClass->$badgeFieldName:$badgeFieldType
                    move-object/from16 v0, p$profileInfoParameter
                    invoke-static {v0, v2}, Lapp/morphe/extension/instagram/FollowsYouIndicator;->show(Ljava/lang/Object;Ljava/lang/Object;)V
                    """.trimIndent(),
                    ExternalLabel("morphe_follows_done", instructions[0]),
                )
            }
        }
    }
}
