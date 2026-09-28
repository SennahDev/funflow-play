package io.github.sennahdev.funflowplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sennahdev.funflowplay.FunFlowApp
import io.github.sennahdev.funflowplay.device.Connection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(app: FunFlowApp, onDismiss: () -> Unit) {
    val connection by app.buttplug.connection.collectAsStateWithLifecycle()
    val devices by app.buttplug.devices.collectAsStateWithLifecycle()
    val scanning by app.buttplug.scanning.collectAsStateWithLifecycle()
    val error by app.buttplug.error.collectAsStateWithLifecycle()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var test by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) { onDispose { app.output.sendFraction(0f) } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Device", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                when (val c = connection) {
                    is Connection.Connected -> "Connected to ${c.serverName}"
                    Connection.Connecting -> "Connecting to ${settings.serverUrl} …"
                    Connection.Disconnected -> "Not connected – is Intiface Central running with its server started?"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = Palette.Hot, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connection == Connection.Disconnected) {
                    Button(onClick = { app.buttplug.connect(settings.serverUrl) }) { Text("Connect") }
                } else {
                    OutlinedButton(onClick = { app.buttplug.disconnect() }) { Text("Disconnect") }
                }
                Button(
                    enabled = connection is Connection.Connected,
                    onClick = { if (scanning) app.buttplug.stopScan() else app.buttplug.startScan() },
                ) { Text(if (scanning) "Stop scanning" else "Scan for devices") }
            }
            Spacer(Modifier.height(16.dp))
            if (devices.isEmpty()) {
                Text("No devices. Turn your toy on and tap “Scan for devices”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (d in devices) {
                val outs = d.outputs.joinToString { o -> if (o.maxStep > 10) "${o.type} (stepless)" else "${o.type} (${o.maxStep} steps)" }
                Text(d.name, style = MaterialTheme.typography.titleSmall)
                Text(outs.ifEmpty { "no supported outputs" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            if (devices.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Test: ${test.toInt()} % device level", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = test,
                    onValueChange = { test = it; app.output.sendFraction(it / 100f) },
                    onValueChangeFinished = { test = 0f; app.output.sendFraction(0f) },
                    valueRange = 0f..100f,
                )
                Text(
                    "Slide up slowly: at which value does your toy start to move? Enter that value in Settings as “Minimum speed” (currently ${settings.minPercent} %).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
