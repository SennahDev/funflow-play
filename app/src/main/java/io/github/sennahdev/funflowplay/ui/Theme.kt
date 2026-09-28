package io.github.sennahdev.funflowplay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

object Palette {
    val Bg = Color(0xFF141519)
    val Surface = Color(0xFF1C1E24)
    val SurfaceHi = Color(0xFF262932)
    val Accent = Color(0xFF4CC9F0)
    val Hot = Color(0xFFFF4D6D)
    val Warm = Color(0xFFFFD166)
    val TextDim = Color(0xFF8A8F9C)

    /** 0..100 -> cool blue .. hot pink (same scale as FunFlow desktop). */
    fun intensity(v: Float): Color {
        val x = v.coerceIn(0f, 100f)
        return when {
            x < 30f -> lerp(Color(0xFF282C38), Accent, x / 30f)
            x < 65f -> lerp(Accent, Warm, (x - 30f) / 35f)
            else -> lerp(Warm, Hot, (x - 65f) / 35f)
        }
    }
}

@Composable
fun FunFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.Bg,
            secondary = Palette.Hot,
            background = Palette.Bg,
            surface = Palette.Surface,
            surfaceVariant = Palette.SurfaceHi,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceHi,
            onSurfaceVariant = Palette.TextDim,
        ),
        content = content,
    )
}
