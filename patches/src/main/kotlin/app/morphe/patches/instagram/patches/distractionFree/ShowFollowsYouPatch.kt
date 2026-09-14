package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM

/**
 * Locates Instagram's DirectUserRowViewBinder bindView method through its
 * stable diagnostic string and the relationship check used by the row.
 */
private object UserListRowBindFingerprint : Fingerprint(
    strings = listOf("DirectUserRowViewBinder - Follow button status unknown"),
    name = "bindView",
    parameters = listOf(
        "I",
        "Landroid/view/View;",
        "Ljava/lang/Object;",
        "Ljava/lang/Object;"
    ),
    returnType = "V",
    filters = listOf(
        app.morphe.patcher.methodCall(
            definingClass = "Lcom/instagram/user/model/UserExtKt;",
            name = "A0V"
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
        val setTextIndex = UserListRowBindFingerprint.instructionMatches[1].index
        UserListRowBindFingerprint.method.addInstructions(
            setTextIndex,
            """
                invoke-static {v8}, Lcom/instagram/user/model/UserExtKt;->A0V(Lcom/instagram/user/model/User;)Z
                move-result v0
                if-eqz v0, :skip_follows_you_indicator

                new-instance v0, Ljava/lang/StringBuilder;
                invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
                invoke-virtual {v0, v10}, Ljava/lang/StringBuilder;->append(Ljava/lang/CharSequence;)Ljava/lang/StringBuilder;
                const-string v1, " • Follows you"
                invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v10

                :skip_follows_you_indicator
            """
        )
    }
}
