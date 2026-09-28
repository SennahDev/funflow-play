package io.github.sennahdev.funflowplay.script

import kotlin.math.abs
import kotlin.math.min

/**
 * The device intensity (0..100) over the whole video, precomputed once in a
 * fixed raster. Playback lookups are O(1) - no work per frame, no GC.
 *
 * - INTENSITY scripts: the script value itself (linear interpolation, like players).
 * - STROKE scripts: movement speed. We integrate the travelled distance and take
 *   distance / time over a sliding window, which is smooth (no flicker between up
 *   and down strokes) and reacts to tempo changes within ~half a window.
 */
class IntensityCurve private constructor(
    private val values: FloatArray,
    val stepMs: Int,
) {
    val durationMs: Int get() = values.size * stepMs

    /** Intensity 0..100 at time [ms] (linear between raster points). */
    fun valueAt(ms: Long): Float {
        if (values.isEmpty() || ms < 0) return 0f
        val f = ms.toFloat() / stepMs
        val i = f.toInt()
        if (i >= values.size - 1) return if (i < values.size) values[i] else 0f
        val frac = f - i
        return values[i] + (values[i + 1] - values[i]) * frac
    }

    /** Peak value per bucket for a heatmap of [buckets] columns over [totalMs]. */
    fun heatmap(buckets: Int, totalMs: Long): FloatArray {
        val out = FloatArray(buckets)
        if (values.isEmpty() || totalMs <= 0) return out
        val perBucket = totalMs.toDouble() / buckets / stepMs
        for (b in 0 until buckets) {
            val from = (b * perBucket).toInt()
            val to = min(values.size, ((b + 1) * perBucket).toInt().coerceAtLeast(from + 1))
            var peak = 0f
            for (i in from until to) if (values[i] > peak) peak = values[i]
            out[b] = peak
        }
        return out
    }

    /** Start of the next active section after [fromMs] (skips the current one), or null. */
    fun nextActiveStart(fromMs: Long, threshold: Float = ACTIVE, minGapMs: Int = 1500): Long? {
        var i = (fromMs / stepMs).toInt() + 1
        val gap = minGapMs / stepMs
        // leave the current active part
        while (i < values.size && values[i] >= threshold) i++
        // need a real pause, then find where it gets active again
        var quiet = 0
        while (i < values.size) {
            if (values[i] >= threshold) {
                if (quiet >= gap) return i.toLong() * stepMs
                quiet = 0
            } else quiet++
            i++
        }
        return null
    }

    /**
     * Start of the section we are in, or of the previous one if we are less than
     * 3 s into the current section (like "previous track" on a music player).
     */
    fun previousActiveStart(fromMs: Long, threshold: Float = ACTIVE, minGapMs: Int = 1500): Long? {
        if (values.isEmpty()) return null
        var i = ((fromMs - 3000) / stepMs).toInt().coerceIn(0, values.size - 1)
        while (i >= 0 && values[i] < threshold) i--  // back to the last active point
        if (i < 0) return null
        return sectionStart(i, threshold, minGapMs / stepMs).toLong() * stepMs
    }

    /** First index of the active section containing [index]; short dips (< gap) don't split sections. */
    private fun sectionStart(index: Int, threshold: Float, gap: Int): Int {
        var j = index
        while (j > 0) {
            if (values[j - 1] >= threshold) {
                j--
                continue
            }
            var quiet = 0
            while (j - 1 - quiet >= 0 && values[j - 1 - quiet] < threshold) quiet++
            if (quiet >= gap || j - 1 - quiet < 0) return j
            j -= quiet
        }
        return 0
    }

    companion object {
        const val ACTIVE = 5f

        fun build(
            script: Funscript,
            kind: ScriptKind,
            fullSpeed: Float = 350f,   // units/s that mean 100 % for stroke scripts
            windowMs: Int = 700,       // smoothing window for stroke scripts
            stepMs: Int = 25,
        ): IntensityCurve {
            if (script.size == 0) return IntensityCurve(FloatArray(0), stepMs)
            val n = script.durationMs / stepMs + 2
            val out = FloatArray(n)
            val at = script.at
            val pos = if (script.inverted) IntArray(script.size) { 100 - script.pos[it] } else script.pos
            when (kind) {
                ScriptKind.INTENSITY -> fillIntensity(out, at, pos, stepMs)
                ScriptKind.STROKE -> fillStrokeSpeed(out, at, pos, stepMs, windowMs, fullSpeed)
            }
            return IntensityCurve(out, stepMs)
        }

        private fun fillIntensity(out: FloatArray, at: IntArray, pos: IntArray, stepMs: Int) {
            var seg = 0
            for (i in out.indices) {
                val t = i * stepMs
                while (seg < at.size - 1 && at[seg + 1] <= t) seg++
                out[i] = when {
                    t <= at[0] -> pos[0].toFloat()
                    seg >= at.size - 1 -> pos.last().toFloat()
                    else -> {
                        val span = (at[seg + 1] - at[seg]).toFloat()
                        pos[seg] + (pos[seg + 1] - pos[seg]) * ((t - at[seg]) / span)
                    }
                }
            }
        }

        private fun fillStrokeSpeed(
            out: FloatArray, at: IntArray, pos: IntArray, stepMs: Int, windowMs: Int, fullSpeed: Float,
        ) {
            // cumulative travelled distance at each action
            val cum = FloatArray(at.size)
            for (k in 1 until at.size) cum[k] = cum[k - 1] + abs(pos[k] - pos[k - 1])
            fun distanceAt(t: Float): Float {
                if (t <= at[0]) return 0f
                if (t >= at.last()) return cum.last()
                var lo = 0
                var hi = at.size - 1
                while (hi - lo > 1) {
                    val mid = (lo + hi) ushr 1
                    if (at[mid] <= t) lo = mid else hi = mid
                }
                val span = (at[hi] - at[lo]).toFloat()
                return cum[lo] + (cum[hi] - cum[lo]) * ((t - at[lo]) / span)
            }
            val half = windowMs / 2f
            for (i in out.indices) {
                val t = (i * stepMs).toFloat()
                val speed = (distanceAt(t + half) - distanceAt(t - half)) / windowMs * 1000f
                out[i] = min(100f, speed / fullSpeed * 100f)
            }
        }
    }
}
