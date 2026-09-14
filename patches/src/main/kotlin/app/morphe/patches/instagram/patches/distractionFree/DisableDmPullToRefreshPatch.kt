package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.util.returnEarly

private object DmPullToRefreshFingerprint : Fingerprint(
    returnType = "Z",
    strings = listOf("direct_inbox_pull_to_refresh"),
    definingClass = "LX/08Pq;"
)

@Suppress("unused")
val disableDmPullToRefreshPatch = bytecodePatch(
    name = "Disable DM pull-to-refresh",
    description = "Disables pull-to-refresh in the Instagram Direct inbox without disabling refresh elsewhere.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        DmPullToRefreshFingerprint.classDef.methods.first {
            it.name == "A06" && it.returnType == "Z"
        }.returnEarly(false)
    }
}
