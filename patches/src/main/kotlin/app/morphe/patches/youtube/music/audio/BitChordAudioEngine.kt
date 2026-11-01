package app.morphe.patches.youtube.music.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standalone DSP primitives for the BitChord-to-Morp research port.
 * These are not active in YouTube Music until version-specific bytecode hooks
 * connect decoded PCM and the processors to YouTube Music's Media3 pipeline.
 */
object BitChordAudioEngine {
    data class BeatGrid(val bpm: Double, val beatPeriodSeconds: Double, val phaseSeconds: Double, val confidence: Double)
    data class TransitionPlan(val durationMs: Long, val outgoingBpm: Double?, val incomingBpm: Double?, val tempoRatio: Double, val beatAligned: Boolean, val phaseOffsetSeconds: Double)
    enum class FilterType { LOW_PASS, HIGH_PASS, PEAKING }
    data class EqBand(val type: FilterType, val frequencyHz: Double, val q: Double = 0.707, val gainDb: Double = 0.0)

    /** Lightweight onset-envelope autocorrelation estimator for normalized mono PCM. */
    fun estimateBeatGrid(pcm: FloatArray, sampleRate: Int): BeatGrid? {
        if (sampleRate < 8_000 || pcm.size < sampleRate * 4) return null
        val hop = max(1, sampleRate / 100)
        val envelope = ArrayList<Double>(pcm.size / hop)
        var previousRms = 0.0
        var offset = 0
        while (offset + hop <= pcm.size) {
            var energy = 0.0
            for (i in offset until offset + hop) { val x = pcm[i].toDouble(); energy += x * x }
            val rms = sqrt(energy / hop)
            envelope += max(0.0, rms - previousRms)
            previousRms = rms
            offset += hop
        }
        if (envelope.size < 300) return null
        val mean = envelope.average()
        val centered = DoubleArray(envelope.size) { envelope[it] - mean }
        // At a 10 ms envelope hop this covers approximately 40–240 BPM.
        // The previous 34–100 lag window silently excluded slow and fast material.
        val minLag = 25
        val maxLag = min(150, centered.size / 3)
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        val lagScores = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var dot = 0.0; var left = 0.0; var right = 0.0
            for (i in lag until centered.size) {
                val a = centered[i]; val b = centered[i - lag]
                dot += a * b; left += a * a; right += b * b
            }
            val score = if (left > 0.0 && right > 0.0) dot / sqrt(left * right) else 0.0
            lagScores[lag] = score
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag < 0 || bestScore < 0.08) return null

        // Sparse or strongly accented material can correlate equally well at a multiple
        // of the true beat period. Prefer a credible shorter divisor to avoid reporting
        // half-time BPM (for example 45 instead of 90) or a three-beat subharmonic.
        val dominantLag = bestLag
        for (lag in minLag until dominantLag) {
            val multiple = dominantLag.toDouble() / lag
            val nearestMultiple = round(multiple)
            if (nearestMultiple in 2.0..6.0 &&
                abs(multiple - nearestMultiple) <= 0.08 &&
                lagScores[lag] >= bestScore * 0.65
            ) {
                bestLag = lag
                break
            }
        }
        val selectedScore = lagScores[bestLag]
        val period = bestLag * hop.toDouble() / sampleRate
        val bpm = 60.0 / period
        var bestPhase = 0
        var phaseEnergy = Double.NEGATIVE_INFINITY
        for (phase in 0 until bestLag) {
            var sum = 0.0; var count = 0; var i = phase
            while (i < centered.size) { sum += max(0.0, centered[i]); count++; i += bestLag }
            val score = if (count == 0) 0.0 else sum / count
            if (score > phaseEnergy) { phaseEnergy = score; bestPhase = phase }
        }
        // Confidence reflects periodicity strength, not uniqueness of a tempo octave.
        // Half/double-time ambiguity is common in music and is handled by conservative
        // transition limits rather than incorrectly collapsing confidence to zero.
        val confidence = selectedScore.coerceIn(0.0, 1.0)
        return BeatGrid(bpm, period, bestPhase * hop.toDouble() / sampleRate, confidence)
    }

    /** Conservative beat-aware plan. It never asks for more than the configured tempo deviation. */
    fun planTransition(outgoing: BeatGrid?, incoming: BeatGrid?, requestedDurationMs: Long, maxTempoDeviation: Double = 0.06): TransitionPlan {
        val duration = requestedDurationMs.coerceIn(0L, 12_000L)
        if (outgoing == null || incoming == null || outgoing.confidence < 0.12 || incoming.confidence < 0.12)
            return TransitionPlan(duration, outgoing?.bpm, incoming?.bpm, 1.0, false, 0.0)
        val rawRatio = outgoing.bpm / incoming.bpm
        val ratio = rawRatio.coerceIn(1.0 - maxTempoDeviation, 1.0 + maxTempoDeviation)
        val aligned = abs(rawRatio - ratio) < 0.0001
        val phase = (outgoing.phaseSeconds - incoming.phaseSeconds) % incoming.beatPeriodSeconds
        return TransitionPlan(duration, outgoing.bpm, incoming.bpm, ratio, aligned, phase)
    }

    /** Stateful transposed direct-form-II biquad. */
    class Biquad(sampleRate: Double, band: EqBand) {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0
        init {
            require(sampleRate >= 8_000.0)
            require(band.frequencyHz > 0.0 && band.frequencyHz < sampleRate / 2.0)
            require(band.q > 0.0)
            val omega = 2.0 * PI * band.frequencyHz / sampleRate
            val c = cos(omega); val s = sin(omega); val alpha = s / (2.0 * band.q)
            val b: DoubleArray
            val a: DoubleArray
            when (band.type) {
                FilterType.LOW_PASS -> { b = doubleArrayOf((1-c)/2, 1-c, (1-c)/2); a = doubleArrayOf(1+alpha, -2*c, 1-alpha) }
                FilterType.HIGH_PASS -> { b = doubleArrayOf((1+c)/2, -(1+c), (1+c)/2); a = doubleArrayOf(1+alpha, -2*c, 1-alpha) }
                FilterType.PEAKING -> {
                    val gain = exp(ln(10.0) * band.gainDb / 40.0)
                    b = doubleArrayOf(1+alpha*gain, -2*c, 1-alpha*gain)
                    a = doubleArrayOf(1+alpha/gain, -2*c, 1-alpha/gain)
                }
            }
            b0=b[0]/a[0]; b1=b[1]/a[0]; b2=b[2]/a[0]; a1=a[1]/a[0]; a2=a[2]/a[0]
        }
        fun process(sample: Float): Float {
            val x=sample.toDouble(); val y=b0*x+z1
            z1=b1*x-a1*y+z2; z2=b2*x-a2*y
            return y.toFloat()
        }
    }

    /** Interleaved multichannel echo-out. Construct outside the real-time audio callback. */
    class EchoOut(sampleRate: Int, channels: Int, delayMs: Int, feedback: Float = 0.35f, wet: Float = 0.45f) {
        private val channelCount = channels.also { require(it in 1..8) }
        private val delayFrames = (sampleRate.also { require(it >= 8_000) } * delayMs.coerceIn(40, 1500) / 1000).coerceAtLeast(1)
        private val ring = FloatArray(delayFrames * channelCount)
        private var cursor=0
        private val fb=feedback.coerceIn(0f,0.75f)
        private val mix=wet.coerceIn(0f,1f)
        private val damping=FloatArray(channels)
        init { /* Constructor arguments are validated before allocating the delay ring. */ }
        fun process(interleaved: FloatArray, frames: Int = interleaved.size/channelCount) {
            require(frames*channelCount<=interleaved.size)
            for (frame in 0 until frames) {
                val rb=cursor*channelCount; val sb=frame*channelCount
                for (channel in 0 until channelCount) {
                    val input=interleaved[sb+channel]; val delayed=ring[rb+channel]
                    damping[channel]+=0.22f*(delayed-damping[channel])
                    ring[rb+channel]=input+damping[channel]*fb
                    interleaved[sb+channel]=input*(1f-mix)+delayed*mix
                }
                cursor++; if (cursor==delayFrames) cursor=0
            }
        }
        fun clear() { ring.fill(0f); damping.fill(0f); cursor=0 }
    }


    /**
     * Block-level RMS loudness targeter. This is intentionally an RMS utility, not an
     * EBU R128/LUFS meter: it needs a calibrated loudness analyzer before production use.
     * Apply gain with a conservative peak ceiling to avoid clipping.
     */
    data class GainAnalysis(val rms: Double, val peak: Double, val gain: Double)

    fun analyseAndSetGain(
        samples: FloatArray,
        targetRms: Double = 0.18,
        maximumGainDb: Double = 9.0,
        peakCeiling: Double = 0.95,
    ): GainAnalysis {
        require(samples.isNotEmpty())
        require(targetRms in 0.001..1.0)
        require(maximumGainDb in 0.0..24.0)
        require(peakCeiling in 0.5..1.0)
        var energy = 0.0
        var peak = 0.0
        for (sample in samples) {
            require(sample.isFinite())
            val value = sample.toDouble()
            energy += value * value
            peak = max(peak, abs(value))
        }
        val rms = sqrt(energy / samples.size)
        if (rms <= 1e-9) return GainAnalysis(rms, peak, 1.0)
        val maxGain = exp(ln(10.0) * maximumGainDb / 20.0)
        val rmsGain = targetRms / rms
        val peakGain = if (peak <= 1e-9) maxGain else peakCeiling / peak
        return GainAnalysis(rms, peak, min(maxGain, min(rmsGain, peakGain)).coerceAtLeast(0.0))
    }

    /** Smooth peak limiter. Construct once and process on the audio thread without allocations. */
    class PeakLimiter(
        private val sampleRate: Int,
        ceiling: Float = 0.95f,
        attackMs: Double = 2.0,
        releaseMs: Double = 80.0,
    ) {
        private val limit = ceiling.coerceIn(0.5f, 1.0f)
        private val attack = exp(-1.0 / (sampleRate.also { require(it >= 8_000) } * attackMs.coerceIn(0.1, 50.0) / 1000.0))
        private val release = exp(-1.0 / (sampleRate * releaseMs.coerceIn(5.0, 500.0) / 1000.0))
        private var gain = 1.0
        init { require(ceiling.isFinite()); require(attackMs > 0.0 && releaseMs > 0.0) }

        fun process(samples: FloatArray) {
            for (i in samples.indices) {
                val input = samples[i]
                val magnitude = abs(input.toDouble())
                val desired = if (magnitude > limit.toDouble()) limit.toDouble() / magnitude else 1.0
                val coefficient = if (desired < gain) attack else release
                gain = desired + coefficient * (gain - desired)
                samples[i] = (input.toDouble() * gain).coerceIn(-limit.toDouble(), limit.toDouble()).toFloat()
            }
        }

        fun reset() { gain = 1.0 }
    }

    /** Cascaded per-channel multiband EQ. Build/configure outside the audio callback. */
    class MultiBandEq(sampleRate: Int, channels: Int, bands: List<EqBand>) {
        private val channelCount = channels.also { require(it in 1..8) }
        private val filters = Array(channelCount) {
            bands.map { Biquad(sampleRate.toDouble(), it) }
        }
        fun process(interleaved: FloatArray) {
            require(interleaved.size % channelCount == 0)
            var i = 0
            while (i < interleaved.size) {
                val channelFilters = filters[(i / channelCount) % channelCount]
                var sample = interleaved[i]
                for (filter in channelFilters) sample = filter.process(sample)
                interleaved[i] = sample
                i++
            }
        }
    }


}
