package app.morphe.patches.youtube.music.audio

object BitChordAudioEngineSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        val fallback=BitChordAudioEngine.planTransition(null,null,99_000)
        check(fallback.durationMs==12_000L && !fallback.beatAligned)
        val out=BitChordAudioEngine.BeatGrid(120.0,0.5,0.0,0.9)
        val incoming=BitChordAudioEngine.BeatGrid(100.0,0.6,0.1,0.9)
        val plan=BitChordAudioEngine.planTransition(out,incoming,5_000)
        check(plan.tempoRatio in 0.94..1.06 && !plan.beatAligned)
        val eq=BitChordAudioEngine.Biquad(48_000.0,BitChordAudioEngine.EqBand(BitChordAudioEngine.FilterType.LOW_PASS,12_000.0))
        var y=0f; repeat(128) { y=eq.process(if(it==0) 0.5f else 0f) }; check(y.isFinite())
        val echo=BitChordAudioEngine.EchoOut(48_000,2,250)
        val pcm=FloatArray(48_000*2); pcm[0]=0.5f; echo.process(pcm)
        check(pcm.all { it.isFinite() } && pcm.maxOf { kotlin.math.abs(it) }<=1.0f); echo.clear()
        val rate = 8_000
        for (bpm in listOf(40, 60, 90, 120, 180, 240)) {
            val samples = FloatArray(rate * 12)
            val beatFrames = (rate * 60.0 / bpm).toInt()
            val pulseFrames = 80
            for (n in 0 until (12 * bpm / 60)) {
                for (i in 0 until pulseFrames) {
                    val index = n * beatFrames + i
                    if (index < samples.size) {
                        samples[index] = (0.8 * kotlin.math.exp(-i / 18.0)).toFloat()
                    }
                }
            }
            val grid = BitChordAudioEngine.estimateBeatGrid(samples, rate)
            val tolerance = bpm * 0.06
            check(grid != null && kotlin.math.abs(grid.bpm - bpm) <= tolerance && grid.confidence > 0.2) {
                "Expected $bpm BPM (±6%), got $grid"
            }
        }

        val gain = BitChordAudioEngine.analyseAndSetGain(FloatArray(1024) { 0.05f })
        check(gain.gain in 1.0..3.0 && gain.peak < 1.0)
        val limiter = BitChordAudioEngine.PeakLimiter(48_000)
        val hot = FloatArray(256) { 1.4f }
        limiter.process(hot)
        check(hot.all { it.isFinite() && kotlin.math.abs(it) <= 0.95f })
        val multiband = BitChordAudioEngine.MultiBandEq(48_000, 2, listOf(
            BitChordAudioEngine.EqBand(BitChordAudioEngine.FilterType.PEAKING, 1_000.0, gainDb = 3.0)
        ))
        val stereo = FloatArray(512) { 0.1f }
        multiband.process(stereo)
        check(stereo.all { it.isFinite() })
        check(runCatching { BitChordAudioEngine.EchoOut(48_000, 0, 250) }.isFailure)
        println("BitChord audio engine smoke tests passed.")
    }
}
