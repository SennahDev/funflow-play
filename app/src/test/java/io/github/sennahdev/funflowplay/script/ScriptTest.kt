package io.github.sennahdev.funflowplay.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {
    private fun script(vararg pts: Pair<Int, Int>, inverted: Boolean = false) =
        Funscript(pts.map { it.first }.toIntArray(), pts.map { it.second }.toIntArray(), inverted, null)

    @Test
    fun parseNormalizesSortsAndDedupes() {
        val s = Funscript.parse(
            """{"inverted":false,"actions":[{"at":2000,"pos":150},{"at":1000.4,"pos":49.6},
               {"at":2000,"pos":20},{"at":-5,"pos":10},{"pos":5}],
               "metadata":{"funflow":{"kind":"intensity"}}}""",
        )
        assertEquals(listOf(1000, 2000), s.at.toList())
        assertEquals(listOf(50, 20), s.pos.toList())
        assertEquals("intensity", s.kindHint)
    }

    @Test
    fun detectsStrokeAndIntensityScripts() {
        val stroke = script(*Array(40) { it * 300 to if (it % 2 == 0) 0 else 100 })
        assertEquals(ScriptKind.STROKE, ScriptKindDetector.detect(stroke, "a.funscript"))
        // FunFlow style: levels held with hold points
        val intensity = script(0 to 0, 960 to 0, 1000 to 50, 2960 to 50, 3000 to 90, 5000 to 90)
        assertEquals(ScriptKind.INTENSITY, ScriptKindDetector.detect(intensity, "a.funscript"))
        assertEquals(ScriptKind.INTENSITY, ScriptKindDetector.detect(stroke, "a.vib.funscript"))
    }

    @Test
    fun intensityCurveInterpolates() {
        val c = IntensityCurve.build(script(0 to 0, 1000 to 100, 2000 to 100), ScriptKind.INTENSITY)
        assertEquals(50f, c.valueAt(500), 0.5f)
        assertEquals(100f, c.valueAt(1500), 0.5f)
    }

    @Test
    fun strokeSpeedBecomesIntensity() {
        // full 0..100 strokes every 500 ms = 200 units/s -> 200/350 = 57 %
        val pts = Array(41) { it * 500 to if (it % 2 == 0) 0 else 100 }
        val c = IntensityCurve.build(script(*pts), ScriptKind.STROKE, fullSpeed = 350f, windowMs = 1000)
        assertEquals(57f, c.valueAt(10_000), 1.5f)
        // smooth: no flicker between up and down strokes
        val samples = (5000..15000 step 25).map { c.valueAt(it.toLong()) }
        assertTrue(samples.max() - samples.min() < 2f)
        // silence after the script
        assertEquals(0f, c.valueAt(30_000), 0.01f)
    }

    @Test
    fun invertedFlagIsApplied() {
        val c = IntensityCurve.build(script(0 to 0, 1000 to 0, inverted = true), ScriptKind.INTENSITY)
        assertEquals(100f, c.valueAt(500), 0.01f)
    }

    @Test
    fun sectionNavigation() {
        // active 0-10 s, quiet 10-20 s, active 20-30 s, quiet after
        val c = IntensityCurve.build(
            script(0 to 50, 10_000 to 50, 10_040 to 0, 20_000 to 0, 20_040 to 60, 30_000 to 60, 30_040 to 0, 40_000 to 0),
            ScriptKind.INTENSITY,
        )
        assertEquals(20_000.0, c.nextActiveStart(2_000)!!.toDouble(), 50.0)
        assertNull(c.nextActiveStart(25_000))
        // 8 s into the second section -> its start; right after its start -> the first section
        assertEquals(20_000.0, c.previousActiveStart(28_000)!!.toDouble(), 50.0)
        assertEquals(0.0, c.previousActiveStart(21_000)!!.toDouble(), 50.0)
    }
}
