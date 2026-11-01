package app.morphe.patches.youtube.music.audio

object BitChordFeaturePolicySelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val p = BitChordFeaturePolicies

        val candidates = listOf(
            BitChordFeaturePolicies.AudioCandidate("low", BitChordFeaturePolicies.Quality.LOW, 96, "aac"),
            BitChordFeaturePolicies.AudioCandidate("high", BitChordFeaturePolicies.Quality.HIGH, 256, "opus"),
            BitChordFeaturePolicies.AudioCandidate("lossless", BitChordFeaturePolicies.Quality.LOSSLESS, 1_411, "flac"),
            BitChordFeaturePolicies.AudioCandidate("unauthorised", BitChordFeaturePolicies.Quality.LOSSLESS, 2_000, "flac", isAuthorised = false),
        )
        check(p.chooseQuality(candidates, BitChordFeaturePolicies.Network.MOBILE, BitChordFeaturePolicies.QualityCeilings())?.value == "high")
        check(p.chooseQuality(candidates, BitChordFeaturePolicies.Network.WIFI, BitChordFeaturePolicies.QualityCeilings())?.value == "lossless")
        check(p.chooseQuality(candidates, BitChordFeaturePolicies.Network.WIFI, BitChordFeaturePolicies.QualityCeilings(wifi = BitChordFeaturePolicies.Quality.LOW))?.value == "low")

        val silence = BitChordFeaturePolicies.SilenceGate(BitChordFeaturePolicies.SilenceConfig(minimumSilenceMs = 450))
        val quiet = FloatArray(480)
        repeat(44) { check(!silence.processFrame(quiet, 48_000).shouldSkip) }
        val trigger = silence.processFrame(quiet, 48_000)
        check(trigger.shouldSkip && trigger.skipMs == 450L)
        check(!silence.processFrame(quiet, 48_000).shouldSkip)
        silence.processFrame(FloatArray(480) { 0.2f }, 48_000)
        check(!silence.processFrame(quiet, 48_000).shouldSkip)

        val timer = BitChordFeaturePolicies.SleepTimer.after(nowElapsedMs = 10_000, durationMs = 60_000, fadeOutMs = 5_000)
        check(BitChordFeaturePolicies.SleepTimer.remainingMs(timer, 30_000) == 40_000L)
        check(!BitChordFeaturePolicies.SleepTimer.shouldStop(timer, 69_999, trackEnded = false))
        check(BitChordFeaturePolicies.SleepTimer.shouldStop(timer, 70_000, trackEnded = false))
        val overflowTimer = BitChordFeaturePolicies.SleepTimer.after(Long.MAX_VALUE - 5, 100)
        check(overflowTimer.deadlineElapsedMs == Long.MAX_VALUE)
        val endTimer = BitChordFeaturePolicies.SleepTimer.atEndOfTrack()
        check(!BitChordFeaturePolicies.SleepTimer.shouldStop(endTimer, 1, trackEnded = false))
        check(BitChordFeaturePolicies.SleepTimer.shouldStop(endTimer, 1, trackEnded = true))

        val sources = listOf(
            BitChordFeaturePolicies.Source("not-configured", 100, false, true, true, "bad"),
            BitChordFeaturePolicies.Source("unauthorised", 100, true, false, true, "bad"),
            BitChordFeaturePolicies.Source("ytm", 10, true, true, false, "fallback"),
            BitChordFeaturePolicies.Source("lossless-module", 5, true, true, true, "preferred"),
        )
        check(p.chooseSource(sources, preferLossless = true)?.value == "preferred")
        check(p.chooseSource(sources, preferLossless = false)?.value == "fallback")
        check(p.chooseSource(sources.take(2), preferLossless = true) == null)

        val a = BitChordFeaturePolicies.TrackIdentity("Song", "Artist", "Album", 180_000)
        val b = BitChordFeaturePolicies.TrackIdentity("Song", "Artist", "Album", 181_000)
        val wrong = BitChordFeaturePolicies.TrackIdentity("Song", "Different Artist", "Album", 180_000)
        check(p.trackMatchScore(a, b) >= 0.99)
        check(p.trackMatchScore(a, wrong) == 0.0)

        BitChordFeaturePolicies.StreamStats("flac", 96_000, 24, 2, 2_304, "configured source", true, true)
        check(runCatching { BitChordFeaturePolicies.StreamStats("flac", 0, 24, 2, 2_304, "x", true, true) }.isFailure)

        println("BitChord feature policy smoke tests passed.")
    }
}
