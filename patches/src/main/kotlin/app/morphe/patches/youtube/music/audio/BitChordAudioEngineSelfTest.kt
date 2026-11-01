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
        val rate=8_000; val samples=FloatArray(rate*12); val beat=rate/2
        for (n in 0 until 24) for (i in 0 until 80) {
            val index=n*beat+i
            if(index<samples.size) samples[index]=(0.8*kotlin.math.exp(-i/18.0)).toFloat()
        }
        val grid=BitChordAudioEngine.estimateBeatGrid(samples,rate)
        check(grid!=null && grid.bpm in 110.0..130.0) { "Expected ~120 BPM, got $grid" }
        println("BitChord audio engine smoke tests passed.")
    }
}
