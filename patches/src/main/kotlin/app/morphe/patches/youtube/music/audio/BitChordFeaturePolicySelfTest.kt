package app.morphe.patches.youtube.music.audio

object BitChordFeaturePolicySelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val p = BitChordFeaturePolicies

        val candidates = listOf(
            p.AudioCandidate("low", p.Quality.LOW, 96, "aac"),
            p.AudioCandidate("high", p.Quality.HIGH, 256, "opus"),
            p.AudioCandidate("lossless", p.Quality.LOSSLESS, 1_411, "flac"),
            p.AudioCandidate("unauthorised", p.Quality.LOSSLESS, 2_000, "flac", isAuthorised = false),
        )
        check(p.chooseQuality(candidates, p.Network.MOBILE, p.QualityCeilings())?.value == "high")
        check(p.chooseQuality(candidates, p.Network.WIFI, p.QualityCeilings())?.value == "lossless")
        check(p.chooseQuality(candidates, p.Network.WIFI, p.QualityCeilings(wifi = p.Quality.LOW))?.value == "low")

        val silence = p.SilenceGate(p.SilenceConfig(minimumSilenceMs = 450))
        val quiet = FloatArray(480)
        repeat(44) { check(!silence.processFrame(quiet, 48_000).shouldSkip) }
        val trigger = silence.processFrame(quiet, 48_000)
        check(trigger.shouldSkip && trigger.skipMs == 450L)
        check(!silence.processFrame(quiet, 48_000).shouldSkip)
        silence.processFrame(FloatArray(480) { 0.2f }, 48_000)
        check(!silence.processFrame(quiet, 48_000).shouldSkip)

        val timer = p.SleepTimer.after(nowElapsedMs = 10_000, durationMs = 60_000, fadeOutMs = 5_000)
        check(p.SleepTimer.remainingMs(timer, 30_000) == 40_000L)
        check(!p.SleepTimer.shouldStop(timer, 69_999, trackEnded = false))
        check(p.SleepTimer.shouldStop(timer, 70_000, trackEnded = false))
        val endTimer = p.SleepTimer.atEndOfTrack()
        check(!p.SleepTimer.shouldStop(endTimer, 1, trackEnded = false))
        check(p.SleepTimer.shouldStop(endTimer, 1, trackEnded = true))

        val sources = listOf(
            p.Source("not-configured", 100, false, true, true, "bad"),
            p.Source("unauthorised", 100, true, false, true, "bad"),
            p.Source("ytm", 10, true, true, false, "fallback"),
            p.Source("lossless-module", 5, true, true, true, "preferred"),
        )
        check(p.chooseSource(sources, preferLossless = true)?.value == "preferred")
        check(p.chooseSource(sources, preferLossless = false)?.value == "fallback")
        check(p.chooseSource(sources.take(2), preferLossless = true) == null)

        val a = p.TrackIdentity("Song", "Artist", "Album", 180_000)
        val b = p.TrackIdentity("Song", "Artist", "Album", 181_000)
        val wrong = p.TrackIdentity("Song", "Different Artist", "Album", 180_000)
        check(p.trackMatchScore(a, b) >= 0.99)
        check(p.trackMatchScore(a, wrong) == 0.0)

        p.StreamStats("flac", 96_000, 24, 2, 2_304, "configured source", true, true)
        check(runCatching { p.StreamStats("flac", 0, 24, 2, 2_304, "x", true, true) }.isFailure)

        println("BitChord feature policy smoke tests passed.")
    }
}
