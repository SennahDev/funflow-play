package io.github.sennahdev.funflowplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sennahdev.funflowplay.FunFlowApp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: FunFlowApp, onBack: () -> Unit) {
    val s by app.settings.state.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(s.serverUrl) }
    val set = app.settings::update

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Section("Device")
            SliderRow(
                "Minimum speed", "${s.minPercent} %",
                "Your toy doesn't move below this. Every script value above 0 starts here.",
                s.minPercent.toFloat(), 0f..60f,
            ) { v -> set { it.copy(minPercent = v.roundToInt()) } }
            SliderRow("Maximum", "${s.maxPercent} %", "Upper limit for the strongest parts.", s.maxPercent.toFloat(), 30f..100f) { v ->
                set { it.copy(maxPercent = v.roundToInt()) }
            }
            SliderRow(
                "Latency compensation", "${s.latencyMs} ms",
                "Positive = the toy reacts earlier (compensates Bluetooth delay).",
                s.latencyMs.toFloat(), -500f..500f,
            ) { v -> set { it.copy(latencyMs = (v / 10).roundToInt() * 10) } }
            SwitchRow("Connect to Intiface automatically", s.autoConnect) { v -> set { it.copy(autoConnect = v) } }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Intiface address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (url != s.serverUrl) {
                OutlinedButton(onClick = {
                    set { it.copy(serverUrl = url.trim()) }
                    app.buttplug.disconnect()
                    app.buttplug.connect(url.trim())
                }) { Text("Apply & reconnect") }
            }

            Section("Stroke scripts")
            Text(
                "Normal scripts describe up/down strokes. The app turns them into a speed: fast strokes = strong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderRow(
                "Stroke speed for 100 %", "${s.strokeFullSpeed}",
                "Movement (units/s) that means full strength. Lower = stronger overall.",
                s.strokeFullSpeed.toFloat(), 150f..700f,
            ) { v -> set { it.copy(strokeFullSpeed = (v / 10).roundToInt() * 10) } }
            SliderRow(
                "Smoothing", "${s.strokeSmoothingMs} ms",
                "Higher = calmer, lower = reacts faster to tempo changes.",
                s.strokeSmoothingMs.toFloat(), 200f..2000f,
            ) { v -> set { it.copy(strokeSmoothingMs = (v / 50).roundToInt() * 50) } }

            Section("Playback")
            SliderRow("Skip distance", "${s.skipSeconds} s", "Double-tap left/right skips this far.", s.skipSeconds.toFloat(), 5f..30f) { v ->
                set { it.copy(skipSeconds = (v / 5).roundToInt() * 5) }
            }

            Section("Library")
            SwitchRow("Only show videos with a script", s.onlyWithScripts) { v -> set { it.copy(onlyWithScripts = v) } }
            OutlinedButton(onClick = { app.videoInfo.clearThumbnails() }) { Text("Regenerate thumbnails") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
    HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 8.dp))
}

@Composable
private fun SliderRow(
    title: String, value: String, hint: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(value, color = Palette.Accent)
        }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
