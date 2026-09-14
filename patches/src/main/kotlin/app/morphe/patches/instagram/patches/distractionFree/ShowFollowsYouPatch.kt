package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM

/**
 * Locates Instagram 439's profile-user update callback. The callback receives
 * the current profile User and also performs Instagram's own UserExtKt.A0V()
 * relationship check, making it the correct lifecycle point for the indicator.
 */
private object ProfileUserUpdateFingerprint : Fingerprint(
    strings = listOf("UserDetailFragment.onUserUpdatedFromNetworkCallback"),
    name = "A14",
    parameters = listOf(
        "Lcom/instagram/user/model/User;",
        "J",
        "Z",
        "Z",
        "Z"
    ),
    returnType = "V",
    filters = listOf(
        app.morphe.patcher.methodCall(
            definingClass = "Lcom/instagram/user/model/UserExtKt;",
            name = "A0V"
        )
    )
)

@Suppress("unused")
val showFollowsYouPatch = bytecodePatch(
    name = "Show Follows You",
    description = "Shows a Follows you indicator on Instagram profiles for accounts that follow you.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    // The helper is deliberately reflection-based because Instagram's User and
    // UserExtKt classes are obfuscated and are not part of the extension API.
    extendWith("extensions/instagram.mpe")

    execute {
        val relationshipCheckIndex = ProfileUserUpdateFingerprint.instructionMatches[0].index
        ProfileUserUpdateFingerprint.method.addInstructions(
            relationshipCheckIndex,
            """
                invoke-static {v13, v0}, Lapp/morphe/extension/instagram/FollowsYouIndicator;->update(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
    }
}
