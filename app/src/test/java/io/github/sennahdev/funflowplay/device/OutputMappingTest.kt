package io.github.sennahdev.funflowplay.device

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputMappingTest {
    @Test
    fun deadZoneIsSkipped() {
        // toy doesn't move below 22 %: the smallest real value starts there
        assertEquals(0f, OutputMapping.toDeviceFraction(0f, 22, 100), 1e-6f)
        assertEquals(0f, OutputMapping.toDeviceFraction(0.3f, 22, 100), 1e-6f) // rounding noise
        // 1 % already moves, and every step up is still a bit stronger (scaled, not clipped)
        assertEquals(0.2278f, OutputMapping.toDeviceFraction(1f, 22, 100), 1e-4f)
        assertEquals(0.2356f, OutputMapping.toDeviceFraction(2f, 22, 100), 1e-4f)
        assertEquals(0.22f + 0.78f * 0.05f, OutputMapping.toDeviceFraction(5f, 22, 100), 1e-4f)
        assertEquals(0.61f, OutputMapping.toDeviceFraction(50f, 22, 100), 1e-4f)
        assertEquals(1f, OutputMapping.toDeviceFraction(100f, 22, 100), 1e-6f)
        assertEquals(0.8f, OutputMapping.toDeviceFraction(100f, 22, 80), 1e-6f)
    }

    @Test
    fun stepsRoundUpSoAnyNonZeroValueMoves() {
        assertEquals(0, OutputMapping.toStep(0f, 255))
        assertEquals(1, OutputMapping.toStep(0.001f, 255))
        assertEquals(128, OutputMapping.toStep(0.5f, 255))
        assertEquals(255, OutputMapping.toStep(1f, 255))
        // official Intiface: 3 steps
        assertEquals(1, OutputMapping.toStep(0.3f, 3))
        assertEquals(3, OutputMapping.toStep(1f, 3))
    }
}
