package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.util.returnEarly

/**
 * Finds the Direct inbox refresh configuration class through the stable
 * feature-key string. The key is used by the refresh-container construction
 * method, while A06() is the boolean gate that enables pull-to-refresh.
 */
private object DmPullToRefreshClassFingerprint : Fingerprint(
    strings = listOf("direct_inbox_pull_to_refresh")
)

private object DmPullToRefreshFingerprint : Fingerprint(
    classFingerprint = DmPullToRefreshClassFingerprint,
    name = "A06",
    returnType = "Z"
)

/**
 * The DM refresh controller stores the actual RefreshableNestedScrollingParent
 * in field A00. Disabling that view makes onStartNestedScroll() reject the
 * pull gesture, which is the actual UI enforcement point.
 */
private object DmRefreshContainerConstructorFingerprint : Fingerprint(
    definingClass = "LX/06uL;",
    name = "<init>",
    parameters = listOf("Landroid/view/View;", "LX/0Oen;", "Z"),
    filters = listOf(
        app.morphe.patcher.fieldAccess(
            definingClass = "this",
            name = "A00",
            type = "Lcom/instagram/ui/widget/refresh/RefreshableNestedScrollingParent;"
        )
    )
)

@Suppress("unused")
val disableDmPullToRefreshPatch = bytecodePatch(
    name = "Disable DM pull-to-refresh",
    description = "Disables pull-to-refresh in the Instagram Direct inbox without disabling refresh elsewhere.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        // Disable the feature/config gate.
        DmPullToRefreshFingerprint.method.returnEarly(false)

        // Disable the actual DM RefreshableNestedScrollingParent. The field
        // assignment matched above is: iput-object v1, v2, LX/06uL;->A00:...
        // v1 therefore holds the refresh parent and v0 is a free local register.
        val fieldIndex = DmRefreshContainerConstructorFingerprint.instructionMatches[0].index
        DmRefreshContainerConstructorFingerprint.method.addInstructions(
            fieldIndex + 1,
            """
                const/4 v0, 0x0
                invoke-virtual {v1, v0}, Landroid/view/View;->setEnabled(Z)V
            """
        )
    }
}
