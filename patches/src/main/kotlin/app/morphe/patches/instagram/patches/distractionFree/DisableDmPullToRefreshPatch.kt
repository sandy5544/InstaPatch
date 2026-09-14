package app.morphe.patches.instagram.patches.distractionFree

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

@Suppress("unused")
val disableDmPullToRefreshPatch = bytecodePatch(
    name = "Disable DM pull-to-refresh",
    description = "Disables pull-to-refresh in the Instagram Direct inbox without disabling refresh elsewhere.",
    default = true
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        DmPullToRefreshFingerprint.method.returnEarly(false)
    }
}
