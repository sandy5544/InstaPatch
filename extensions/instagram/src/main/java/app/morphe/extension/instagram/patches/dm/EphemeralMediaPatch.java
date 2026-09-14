package app.morphe.extension.instagram.patches.dm;

@SuppressWarnings("unused")
public final class EphemeralMediaPatch {
    private static final String PERMA_KEY = "permanent";

    private EphemeralMediaPatch() {
    }

    public static String makeEphemeralMediaPermanent(Long expireAt, String viewMode) {
        try {
            if (expireAt == null || viewMode == null) return viewMode;

            long currentTime = System.currentTimeMillis();
            long expireAtMillis = expireAt * 1000L;

            if (currentTime <= expireAtMillis && !PERMA_KEY.equals(viewMode)) {
                return PERMA_KEY;
            }
        } catch (Exception ignored) {
            // Preserve Instagram's original view mode if the metadata is malformed.
        }

        return viewMode;
    }
}
