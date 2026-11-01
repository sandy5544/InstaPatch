package app.morphe.patches.youtube.music.audio

import kotlin.math.sqrt

/**
 * Platform-independent policy layer for features advertised by BitChord.
 *
 * These policies deliberately do not perform network requests, access account tokens,
 * seek a YouTube Music player, or write audio. A version-specific Morphe runtime extension
 * must supply those platform actions after its hooks and permissions are verified.
 */
object BitChordFeaturePolicies {
    enum class Network { WIFI, MOBILE, ETHERNET, OFFLINE, OTHER }
    enum class Quality(val rank: Int) { LOW(0), NORMAL(1), HIGH(2), LOSSLESS(3) }

    data class QualityCeilings(
        val wifi: Quality = Quality.LOSSLESS,
        val mobile: Quality = Quality.HIGH,
        val ethernet: Quality = Quality.LOSSLESS,
        val other: Quality = Quality.NORMAL,
    ) {
        fun forNetwork(network: Network): Quality = when (network) {
            Network.WIFI -> wifi
            Network.MOBILE -> mobile
            Network.ETHERNET -> ethernet
            Network.OFFLINE, Network.OTHER -> other
        }
    }

    data class AudioCandidate<T>(
        val value: T,
        val quality: Quality,
        val bitrateKbps: Int,
        val codec: String,
        val isConfiguredSource: Boolean = false,
        val isAuthorised: Boolean = true,
    )

    /** Chooses the best eligible candidate under the current network ceiling. */
    fun <T> chooseQuality(
        candidates: Iterable<AudioCandidate<T>>,
        network: Network,
        ceilings: QualityCeilings,
    ): AudioCandidate<T>? {
        val eligible = candidates.filter {
            it.isAuthorised && it.quality.rank <= ceilings.forNetwork(network).rank &&
                it.bitrateKbps >= 0
        }
        return eligible.maxWithOrNull(
            compareBy<AudioCandidate<T>> { it.quality.rank }
                .thenBy { it.bitrateKbps }
        )
    }

    data class SilenceConfig(
        val thresholdDb: Double = -48.0,
        val minimumSilenceMs: Long = 450,
        val maximumSkipMs: Long = 10_000,
    ) {
        init {
            require(thresholdDb in -90.0..-6.0)
            require(minimumSilenceMs in 100..5_000)
            require(maximumSkipMs in minimumSilenceMs..60_000)
        }
    }

    data class SilenceDecision(
        val silent: Boolean,
        val silenceDurationMs: Long,
        val shouldSkip: Boolean,
        val skipMs: Long,
        val rmsDb: Double,
    )

    /**
     * Stateful RMS silence gate. Call once per PCM frame, outside the audio sink's
     * hard real-time write loop. The caller decides whether and how to seek.
     */
    class SilenceGate(private val config: SilenceConfig = SilenceConfig()) {
        private var silenceMs = 0L
        private var skipIssued = false

        fun reset() {
            silenceMs = 0L
            skipIssued = false
        }

        fun processFrame(samples: FloatArray, sampleRate: Int): SilenceDecision {
            require(sampleRate >= 8_000)
            require(samples.isNotEmpty())
            var energy = 0.0
            for (sample in samples) {
                val x = sample.toDouble().coerceIn(-1.0, 1.0)
                energy += x * x
            }
            val rms = sqrt(energy / samples.size)
            val db = if (rms <= 1e-9) -180.0 else 20.0 * kotlin.math.log10(rms)
            val frameMs = (samples.size * 1000L / sampleRate).coerceAtLeast(1L)
            val silent = db <= config.thresholdDb
            if (silent) {
                silenceMs = (silenceMs + frameMs).coerceAtMost(config.maximumSkipMs)
            } else {
                silenceMs = 0L
                skipIssued = false
            }
            val shouldSkip = silent && !skipIssued && silenceMs >= config.minimumSilenceMs
            if (shouldSkip) skipIssued = true
            return SilenceDecision(
                silent = silent,
                silenceDurationMs = silenceMs,
                shouldSkip = shouldSkip,
                skipMs = if (shouldSkip) silenceMs else 0L,
                rmsDb = db,
            )
        }
    }

    enum class SleepMode { DISABLED, DEADLINE, END_OF_TRACK }
    data class SleepState(
        val mode: SleepMode = SleepMode.DISABLED,
        val deadlineElapsedMs: Long? = null,
        val fadeOutMs: Long = 0L,
    )

    /** Deterministic sleep-timer state. The host supplies monotonic elapsed time and stops playback. */
    object SleepTimer {
        fun after(nowElapsedMs: Long, durationMs: Long, fadeOutMs: Long = 0L): SleepState {
            require(nowElapsedMs >= 0L)
            require(durationMs > 0L)
            require(fadeOutMs in 0L..durationMs)
            val deadline = if (Long.MAX_VALUE - nowElapsedMs < durationMs) Long.MAX_VALUE else nowElapsedMs + durationMs
            return SleepState(SleepMode.DEADLINE, deadline, fadeOutMs)
        }

        fun atEndOfTrack(fadeOutMs: Long = 0L): SleepState {
            require(fadeOutMs >= 0L)
            return SleepState(SleepMode.END_OF_TRACK, null, fadeOutMs)
        }

        fun shouldStop(state: SleepState, nowElapsedMs: Long, trackEnded: Boolean): Boolean =
            when (state.mode) {
                SleepMode.DISABLED -> false
                SleepMode.DEADLINE -> state.deadlineElapsedMs?.let { nowElapsedMs >= it } ?: false
                SleepMode.END_OF_TRACK -> trackEnded
            }

        fun remainingMs(state: SleepState, nowElapsedMs: Long): Long? {
            if (state.mode != SleepMode.DEADLINE) return null
            val deadline = state.deadlineElapsedMs ?: return null
            return (deadline - nowElapsedMs).coerceAtLeast(0L)
        }
    }

    data class StreamStats(
        val codec: String?,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
        val channels: Int?,
        val bitrateKbps: Int?,
        val sourceName: String?,
        val lossless: Boolean,
        val directOutput: Boolean,
    ) {
        init {
            require(sampleRateHz == null || sampleRateHz > 0)
            require(bitDepth == null || bitDepth > 0)
            require(channels == null || channels in 1..32)
            require(bitrateKbps == null || bitrateKbps > 0)
        }
    }

    data class Source<T>(
        val id: String,
        val priority: Int,
        val configuredByUser: Boolean,
        val authorised: Boolean,
        val supportsLossless: Boolean,
        val value: T,
    )

    /** Selects only explicitly configured and authorised sources; no credential or DRM probing. */
    fun <T> chooseSource(sources: Iterable<Source<T>>, preferLossless: Boolean): Source<T>? =
        sources.asSequence()
            .filter { it.configuredByUser && it.authorised }
            .sortedWith(
                compareByDescending<Source<T>> { preferLossless && it.supportsLossless }
                    .thenByDescending { it.priority }
            )
            .firstOrNull()

    data class TrackIdentity(
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
    )

    /** Conservative metadata matcher for source fallbacks; rejects ambiguous title-only matches. */
    fun trackMatchScore(a: TrackIdentity, b: TrackIdentity): Double {
        fun norm(value: String?): String = value.orEmpty()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val titleA = norm(a.title)
        val titleB = norm(b.title)
        val artistA = norm(a.artist)
        val artistB = norm(b.artist)
        if (titleA.isBlank() || artistA.isBlank() || titleB.isBlank() || artistB.isBlank()) return 0.0
        if (titleA != titleB || artistA != artistB) return 0.0
        var score = 0.75
        if (a.album != null && b.album != null && norm(a.album) == norm(b.album)) score += 0.15
        if (a.durationMs != null && b.durationMs != null) {
            val delta = kotlin.math.abs(a.durationMs - b.durationMs)
            if (delta <= 2_000L) score += 0.10
            else if (delta > 10_000L) score -= 0.30
        }
        return score.coerceIn(0.0, 1.0)
    }
}
