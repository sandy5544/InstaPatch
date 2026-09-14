package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM

/**
 * Locates Instagram's DirectUserRowViewBinder bindView method through its
 * stable diagnostic string and the relationship check used by the row.
 * The row keeps the User in v8 and the display-name CharSequence in v10
 * immediately before the target TextView.setText(v9, v10) call.
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
        // The first TextView.setText call is the user display-name row update.
        // At this point v8 is the User and v10 is the display-name text.
        val setTextIndex = UserListRowBindFingerprint.instructionMatches[1].index
        UserListRowBindFingerprint.method.addInstructions(
            setTextIndex,
            """
                invoke-static {v8, v10}, Lapp/morphe/extension/instagram/patches/userlist/FollowsYouIndicator;->append(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v10
            """
        )
    }
}
