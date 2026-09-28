package io.github.sennahdev.funflowplay.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.sennahdev.funflowplay.FunFlowApp
import io.github.sennahdev.funflowplay.data.VideoEntry
import io.github.sennahdev.funflowplay.device.Connection
import io.github.sennahdev.funflowplay.script.Funscript
import io.github.sennahdev.funflowplay.script.IntensityCurve
import io.github.sennahdev.funflowplay.script.ScriptKind
import io.github.sennahdev.funflowplay.script.ScriptKindDetector
import io.github.sennahdev.funflowplay.ui.Palette
import io.github.sennahdev.funflowplay.ui.formatTime

private class LoadedScript(val script: Funscript, val detected: ScriptKind)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(app: FunFlowApp, entry: VideoEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as Activity
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val connection by app.buttplug.connection.collectAsStateWithLifecycle()
    val devices by app.buttplug.devices.collectAsStateWithLifecycle()

    val engine: VideoEngine = remember(entry.uri) {
        ExoEngine(context, Uri.parse(entry.uri), app.positions.get(entry.uri))
    }
    val state by engine.state.collectAsStateWithLifecycle()

    // ---------------------------------------------------------------- script
    var loaded by remember { mutableStateOf<LoadedScript?>(null) }
    var scriptError by remember { mutableStateOf<String?>(null) }
    var kindOverride by remember { mutableStateOf(app.videoPrefs.scriptKind(entry.uri)?.let { ScriptKind.valueOf(it) }) }
    LaunchedEffect(entry.uri) {
        val scriptUri = entry.mainScript ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                val text = context.contentResolver.openInputStream(Uri.parse(scriptUri))!!.use { it.readBytes().decodeToString() }
                val script = Funscript.parse(text)
                LoadedScript(script, ScriptKindDetector.detect(script, entry.mainScriptName))
            }
        }.onSuccess { loaded = it }.onFailure { scriptError = "Can't read the script: ${it.message}" }
    }
    val kind = kindOverride ?: loaded?.detected
    var curve by remember { mutableStateOf<IntensityCurve?>(null) }
    LaunchedEffect(loaded, kind, settings.strokeFullSpeed, settings.strokeSmoothingMs) {
        val l = loaded ?: return@LaunchedEffect
        val k = kind ?: return@LaunchedEffect
        curve = withContext(Dispatchers.Default) {
            IntensityCurve.build(l.script, k, settings.strokeFullSpeed.toFloat(), settings.strokeSmoothingMs)
        }
    }

    // ---------------------------------------------------------------- window
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, view)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(state.width, state.height) {
        if (state.width > 0 && state.height > 0) {
            activity.requestedOrientation = if (state.width >= state.height) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
    }
    DisposableEffect(engine) {
        onDispose {
            app.positions.set(entry.uri, engine.positionMs(), engine.state.value.durationMs)
            app.output.stop()
            engine.release()
        }
    }

    // ---------------------------------------------------------------- sync loop
    var deviceOn by remember { mutableStateOf(true) }
    val position = remember { mutableLongStateOf(0L) }
    val liveLevel = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(engine, curve, deviceOn, settings.minPercent, settings.maxPercent, settings.latencyMs) {
        var lastSave = 0L
        while (true) {
            val pos = engine.positionMs()
            position.longValue = pos
            val playing = engine.state.value.playing
            val level = if (playing && deviceOn) curve?.valueAt(pos + settings.latencyMs) else null
            app.output.update(level, settings.minPercent, settings.maxPercent)
            liveLevel.floatValue = if (level == null) 0f else app.output.currentFraction * 100f
            if (pos - lastSave > 5000 || pos < lastSave) {
                app.positions.set(entry.uri, pos, engine.state.value.durationMs)
                lastSave = pos
            }
            delay(33)
        }
    }

    // ---------------------------------------------------------------- controls
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) } // bump to restart the auto-hide timer
    LaunchedEffect(controls, interaction, state.playing) {
        if (controls && state.playing) {
            delay(3500)
            controls = false
        }
    }
    var skipFlash by remember { mutableStateOf<Pair<Int, Long>?>(null) } // direction, timestamp
    val skipMs = settings.skipSeconds * 1000L
    fun skip(dir: Int) {
        engine.seekTo(engine.positionMs() + dir * skipMs)
        skipFlash = dir to System.nanoTime()
        interaction++
    }
    LaunchedEffect(skipFlash) { if (skipFlash != null) { delay(650); skipFlash = null } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(skipMs) {
            detectTapGestures(
                onTap = { controls = !controls; interaction++ },
                onDoubleTap = { o ->
                    when {
                        o.x < size.width / 3f -> skip(-1)
                        o.x > size.width * 2f / 3f -> skip(1)
                        else -> if (engine.state.value.playing) engine.pause() else engine.play()
                    }
                },
            )
        },
    ) {
        engine.Surface(Modifier.fillMaxSize())

        if (state.buffering && !state.playing) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Accent)
        }
        skipFlash?.let { (dir, _) ->
            Text(
                if (dir < 0) "−${settings.skipSeconds} s" else "+${settings.skipSeconds} s",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(if (dir < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(48.dp)
                    .background(Color(0x88000000), RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        state.error?.let {
            Text("This video can't be played ($it)", color = Palette.Hot, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }

        AnimatedVisibility(visible = controls || state.ended, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color(0xAA000000), 0.25f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0xCC000000)),
                ).safeDrawingPadding(),
            ) {
                // top bar
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Text(entry.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    DeviceChip(
                        connected = connection is Connection.Connected && devices.isNotEmpty(),
                        hasScript = curve != null,
                        on = deviceOn,
                        level = { liveLevel.floatValue },
                        onClick = { deviceOn = !deviceOn; interaction++ },
                    )
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true; interaction++ }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Text("Script type", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            listOf(
                                null to "Automatic (${if (loaded?.detected == ScriptKind.INTENSITY) "intensity" else "stroke"})",
                                ScriptKind.STROKE to "Stroke → speed",
                                ScriptKind.INTENSITY to "Intensity (direct)",
                            ).forEach { (k, label) ->
                                DropdownMenuItem(
                                    leadingIcon = { RadioButton(selected = kindOverride == k, onClick = null) },
                                    text = { Text(label) },
                                    onClick = {
                                        kindOverride = k
                                        app.videoPrefs.setScriptKind(entry.uri, k?.name)
                                        menu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            Text(
                                "${state.formatInfo}\n${engine.name}" + (scriptError?.let { "\n$it" } ?: "") +
                                    (if (entry.mainScript == null) "\nNo script found" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                // center controls
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Default.SkipPrevious, "Previous section", 44.dp, enabled = curve != null) {
                        curve?.previousActiveStart(engine.positionMs())?.let { engine.seekTo(it) }
                        interaction++
                    }
                    RoundButton(Icons.Default.Replay10, "Back", 48.dp) { skip(-1) }
                    RoundButton(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", 68.dp) {
                        if (state.ended) engine.seekTo(0)
                        if (state.playing) engine.pause() else engine.play()
                        interaction++
                    }
                    RoundButton(Icons.Default.Forward10, "Forward", 48.dp) { skip(1) }
                    RoundButton(Icons.Default.SkipNext, "Next section", 44.dp, enabled = curve != null) {
                        curve?.nextActiveStart(engine.positionMs())?.let { engine.seekTo(it) }
                        interaction++
                    }
                }

                // bottom: time + seek bar with script heatmap
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    SeekBar(
                        position = { position.longValue },
                        durationMs = state.durationMs,
                        curve = curve,
                        onSeek = { ms, fast -> engine.seekTo(ms, fast); interaction++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0x55000000)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) Color.White else Color(0x66FFFFFF), modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun DeviceChip(connected: Boolean, hasScript: Boolean, on: Boolean, level: () -> Float, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x66000000)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val active = connected && hasScript && on
        Icon(
            if (on) Icons.Default.Sensors else Icons.Default.SensorsOff, "Device on/off",
            tint = if (active) Palette.Accent else Color(0x99FFFFFF), modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.width(44.dp).height(6.dp)) {
            drawRoundRect(Color(0x33FFFFFF), cornerRadius = CornerRadius(3.dp.toPx()))
            val v = if (active) level() else 0f
            drawRoundRect(
                Palette.intensity(v), size = Size(size.width * v / 100f, size.height),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                !hasScript -> "no script"
                !connected -> "no device"
                !on -> "off"
                else -> "on"
            },
            color = Color.White, fontSize = 12.sp,
        )
    }
}

/** Seek bar with the script's intensity as a heatmap. Draws from state - no recomposition per frame. */
@Composable
private fun SeekBar(position: () -> Long, durationMs: Long, curve: IntensityCurve?, onSeek: (Long, Boolean) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val heat = remember(curve, durationMs) { curve?.heatmap(240, durationMs) }
    val shownPos = dragFraction?.let { (it * durationMs).toLong() } ?: position()

    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shownPos), color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), color = Color(0xCCFFFFFF), fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(30.dp).pointerInput(durationMs) {
                if (durationMs <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var f = (down.position.x / size.width).coerceIn(0f, 1f)
                    dragFraction = f
                    var lastSeek = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            f = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragFraction = f
                            val now = System.currentTimeMillis()
                            if (now - lastSeek > 120) { // live preview while scrubbing (keyframes)
                                onSeek((f * durationMs).toLong(), true)
                                lastSeek = now
                            }
                            change.consume()
                        }
                    }
                    onSeek((f * durationMs).toLong(), false) // exact on release
                    dragFraction = null
                }
            },
        ) {
            val w = size.width
            val trackY = size.height * 0.72f
            // heatmap strip
            heat?.let {
                val bw = w / it.size
                for (i in it.indices) {
                    if (it[i] < 1f) continue
                    drawRect(Palette.intensity(it[i]), topLeft = Offset(i * bw, 0f), size = Size(bw + 0.5f, size.height * 0.4f))
                }
            }
            val frac = if (durationMs > 0) ((dragFraction ?: (position().toFloat() / durationMs))).coerceIn(0f, 1f) else 0f
            drawLine(Color(0x55FFFFFF), Offset(0f, trackY), Offset(w, trackY), strokeWidth = 4.dp.toPx())
            drawLine(Palette.Accent, Offset(0f, trackY), Offset(w * frac, trackY), strokeWidth = 4.dp.toPx())
            drawCircle(Color.White, radius = (if (dragFraction != null) 9 else 7).dp.toPx(), center = Offset(w * frac, trackY))
        }
    }
}
