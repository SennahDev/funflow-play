package io.github.sennahdev.funflowplay.script

import org.json.JSONObject

/** A parsed funscript: sorted, unique timestamps (ms) and positions 0..100. */
class Funscript(
    val at: IntArray,
    val pos: IntArray,
    val inverted: Boolean,
    /** Optional hint written by FunFlow: "intensity" or "stroke". */
    val kindHint: String?,
) {
    val size: Int get() = at.size
    val durationMs: Int get() = if (at.isEmpty()) 0 else at.last()

    companion object {
        fun parse(text: String): Funscript {
            val root = JSONObject(text)
            val arr = root.optJSONArray("actions") ?: throw IllegalArgumentException("no actions")
            // Normalize like FunFlow: sort, clamp, last point wins on duplicate times.
            val byTime = java.util.TreeMap<Int, Int>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("at") || !o.has("pos")) continue
                val t = Math.round(o.getDouble("at")).toInt()
                if (t < 0) continue
                byTime[t] = Math.round(o.getDouble("pos")).toInt().coerceIn(0, 100)
            }
            val meta = root.optJSONObject("metadata")
            val hint = meta?.optJSONObject("funflow")?.optString("kind")?.takeIf { it.isNotEmpty() }
            return Funscript(
                at = byTime.keys.toIntArray(),
                pos = byTime.values.toIntArray(),
                inverted = root.optBoolean("inverted", false),
                kindHint = hint,
            )
        }
    }
}

enum class ScriptKind { STROKE, INTENSITY }

object ScriptKindDetector {
    /**
     * Stroke scripts move up and down all the time; intensity scripts (FunFlow,
     * vibration scripts) hold levels, which needs pairs of equal values.
     */
    fun detect(script: Funscript, fileName: String): ScriptKind {
        val name = fileName.lowercase()
        if (name.endsWith(".vib.funscript")) return ScriptKind.INTENSITY
        when (script.kindHint) {
            "intensity" -> return ScriptKind.INTENSITY
            "stroke" -> return ScriptKind.STROKE
        }
        if (script.size < 3) return ScriptKind.INTENSITY
        var flat = 0
        for (i in 1 until script.size) if (script.pos[i] == script.pos[i - 1]) flat++
        return if (flat.toFloat() / (script.size - 1) >= 0.25f) ScriptKind.INTENSITY else ScriptKind.STROKE
    }
}
