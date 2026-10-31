package app.morphe.patches.youtube.music.audio

/**
 * Pure selection policy for the YouTube Music Force Opus patch.
 *
 * The bytecode integration must call this policy at the audio candidate
 * replacement point in Lyop.n, after preserving the app's existing group key.
 * Video candidates must never be passed to this selector.
 */
internal object OpusPreference {
    private const val OPUS_CODEC = "opus"

    data class Candidate<T>(
        val groupKey: String,
        val codec: String,
        val qualityOrdinal: Int,
        val value: T,
    )

    /**
     * Selects one candidate per existing group.
     *
     * Opus wins over every non-Opus candidate in the same group. If multiple
     * Opus candidates exist, the highest existing quality ordinal wins.
     * If a group has no Opus candidate, the highest-quality original candidate
     * wins, preserving YouTube Music's fallback behavior.
     *
     * LinkedHashMap preserves the first-seen group order. maxByOrNull preserves
     * the first candidate on equal ordinals, matching a stable first-wins tie.
     */
    fun <T> selectPerGroup(candidates: Iterable<Candidate<T>>): List<Candidate<T>> {
        val groups = LinkedHashMap<String, MutableList<Candidate<T>>>()

        for (candidate in candidates) {
            groups.getOrPut(candidate.groupKey) { mutableListOf() }.add(candidate)
        }

        return groups.values.map { group ->
            val opusCandidates = group.filter { it.codec.equals(OPUS_CODEC, ignoreCase = true) }
            (opusCandidates.ifEmpty { group }).maxByOrNull { it.qualityOrdinal }!!
        }
    }
}
