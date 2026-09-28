package io.github.sennahdev.funflowplay.device

import android.os.SystemClock
import kotlin.math.ceil

/**
 * Maps script intensity (0..100) to device steps and sends only real changes.
 *
 * Many toys don't move at all below some level (e.g. ~22 %). With [minPercent]
 * every non-zero script value starts at that level, so quiet parts of a script
 * are still felt instead of disappearing in the dead zone.
 */
object OutputMapping {
    /**
     * Only a real 0 is "off". 1 % already maps to the minimum level; the 0.5 margin
     * only swallows tiny rounding noise from converted stroke scripts at rest.
     */
    const val OFF_BELOW = 0.5f

    fun toDeviceFraction(level: Float, minPercent: Int, maxPercent: Int): Float {
        if (level < OFF_BELOW) return 0f
        val lo = minPercent / 100f
        val hi = maxOf(lo, maxPercent / 100f)
        return lo + (hi - lo) * (level.coerceIn(0f, 100f) / 100f)
    }

    fun toStep(fraction: Float, maxStep: Int): Int =
        if (fraction <= 0f) 0 else ceil(fraction * maxStep - 1e-4f).toInt().coerceIn(1, maxStep)
}

class OutputController(private val client: ButtplugClient) {
    private val lastStep = HashMap<Long, Int>()
    private val lastSent = HashMap<Long, Long>()

    /** Last value sent (0..1) - for the live indicator in the UI. */
    @Volatile var currentFraction: Float = 0f
        private set

    /** Call ~30x per second. `level` null = off. */
    fun update(level: Float?, minPercent: Int, maxPercent: Int) {
        val fraction = if (level == null) 0f else OutputMapping.toDeviceFraction(level, minPercent, maxPercent)
        currentFraction = fraction
        sendFraction(fraction)
    }

    /** Raw device level 0..1 (device test in the settings). */
    fun sendFraction(fraction: Float) {
        val now = SystemClock.elapsedRealtime()
        for (device in client.devices.value) {
            for (out in device.outputs) {
                if (out.maxStep <= 0) continue
                val key = device.index.toLong() shl 32 or out.featureIndex.toLong()
                val step = OutputMapping.toStep(fraction, out.maxStep)
                if (lastStep[key] == step) continue
                // Respect the device's message gap, but never delay a stop.
                val gap = maxOf(device.messageGapMs, MIN_GAP_MS)
                if (step != 0 && now - (lastSent[key] ?: 0L) < gap) continue
                client.output(device.index, out, step)
                lastStep[key] = step
                lastSent[key] = now
            }
        }
    }

    fun stop() {
        currentFraction = 0f
        lastStep.clear()
        client.stopAll()
    }

    /** Forget what was sent (e.g. after the device list changed). */
    fun reset() {
        lastStep.clear()
    }

    private companion object {
        const val MIN_GAP_MS = 40
    }
}
