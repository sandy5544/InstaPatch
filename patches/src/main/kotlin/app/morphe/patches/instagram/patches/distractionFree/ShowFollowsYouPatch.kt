package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM

/**
 * Locates the Instagram user-list row binder. The row reads the user's
 * relationship state and the display-name string before updating its
 * TextView.
 */
private object UserListRowBindFingerprint : Fingerprint(
    parameters = listOf("LX/03Tj;", "I"),
    returnType = "V",
    filters = listOf(
        app.morphe.patcher.methodCall(
            definingClass = "Lcom/instagram/user/model/UserExtKt;",
            name = "A0V"
        ),
        app.morphe.patcher.methodCall(
            definingClass = "Lcom/instagram/user/model/LiveTreeUserDict;",
            name = "C8Z"
        ),
        app.morphe.patcher.methodCall(
            definingClass = "Landroid/widget/TextView;",
            name = "setText"
        )
    )
)

@Suppress("unused")
val showFollowsYouPatch = bytecodePatch(
    name = "Show Follows You in followers/following",
    description = "Shows a Follows you indicator next to users who follow the logged-in account in followers and following lists.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        // The row keeps the User object in v1. Re-read the relationship at the
        // point where the display name is ready, then append the indicator to
        // that same TextView so it stays on the undername line.
        val setTextIndex = UserListRowBindFingerprint.instructionMatches[2].index
        UserListRowBindFingerprint.method.addInstructions(
            setTextIndex,
            """
                invoke-static {v1}, Lcom/instagram/user/model/UserExtKt;->A0V(Lcom/instagram/user/model/User;)Z
                move-result v31
                if-eqz v31, :skip_follows_you_indicator

                new-instance v29, Ljava/lang/StringBuilder;
                invoke-direct {v29, v8}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
                const-string v30, " • Follows you"
                invoke-virtual {v29, v30}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v29}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v8

                :skip_follows_you_indicator
            """
        )
    }
}
