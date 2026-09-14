package app.morphe.patches.instagram.patches.distractionFree

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.util.returnEarly

private object PrimeModsIsPremiumFingerprint : Fingerprint(
    custom = { methodDef, _ ->
        methodDef.definingClass.startsWith("Lcom/primemods/") &&
            methodDef.name == "isPremium"
    },
    returnType = "Z"
)

@Suppress("unused")
val enablePrimeModsPremiumPatch = bytecodePatch(
    name = "Enable PrimeMods Premium",
    description = "Forces the PrimeMods premium entitlement to enabled locally.",
    default = false
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    execute {
        PrimeModsIsPremiumFingerprint.method.returnEarly(true)
    }
}
