package io.github.sennahdev.funflowplay.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import io.github.sennahdev.funflowplay.FunFlowApp
import io.github.sennahdev.funflowplay.data.SortOrder
import io.github.sennahdev.funflowplay.data.VideoEntry
import io.github.sennahdev.funflowplay.device.Connection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: FunFlowApp, onOpen: (VideoEntry) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val entries by app.library.entries.collectAsStateWithLifecycle()
    val scanning by app.library.scanning.collectAsStateWithLifecycle()
    val connection by app.buttplug.connection.collectAsStateWithLifecycle()
    val devices by app.buttplug.devices.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var deviceSheet by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.settings.update { it.copy(libraryTreeUri = uri.toString()) }
            app.library.clear()
            scope.launch { app.library.scan(uri) }
        }
    }

    // Refresh in the background on start; the cached list is shown meanwhile.
    LaunchedEffect(settings.libraryTreeUri) {
        settings.libraryTreeUri?.let { app.library.scan(Uri.parse(it)) }
    }

    val shown = remember(entries, query, settings.onlyWithScripts, settings.sortOrder) {
        entries.asSequence()
            .filter { !settings.onlyWithScripts || it.hasScript }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true) }
            .let { seq ->
                when (settings.sortOrder) {
                    SortOrder.NAME -> seq.sortedBy { it.name.lowercase() }
                    SortOrder.NEWEST -> seq.sortedByDescending { it.modified }
                }
            }.toList()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search …") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text("FunFlow Play", fontWeight = FontWeight.SemiBold)
                            if (entries.isNotEmpty()) {
                                Text(
                                    "${shown.size} videos · ${shown.count { it.hasScript }} with script",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("Name") }, onClick = {
                                app.settings.update { it.copy(sortOrder = SortOrder.NAME) }; sortMenu = false
                            })
                            DropdownMenuItem(text = { Text("Newest first") }, onClick = {
                                app.settings.update { it.copy(sortOrder = SortOrder.NEWEST) }; sortMenu = false
                            })
                            DropdownMenuItem(
                                text = { Text(if (settings.onlyWithScripts) "Show all videos" else "Only videos with a script") },
                                onClick = { app.settings.update { it.copy(onlyWithScripts = !it.onlyWithScripts) }; sortMenu = false },
                            )
                            DropdownMenuItem(text = { Text("Change folder …") }, onClick = { sortMenu = false; pickFolder.launch(null) })
                        }
                    }
                    IconButton(onClick = { deviceSheet = true }) {
                        val tint = when {
                            connection is Connection.Connected && devices.isNotEmpty() -> Palette.Accent
                            connection is Connection.Connected -> Palette.Warm
                            else -> Palette.TextDim
                        }
                        Icon(Icons.Default.Sensors, "Device", tint = tint)
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        if (settings.libraryTreeUri == null) {
            EmptyState(Modifier.padding(padding)) { pickFolder.launch(null) }
        } else {
            PullToRefreshBox(
                isRefreshing = scanning,
                onRefresh = { settings.libraryTreeUri?.let { scope.launch { app.library.scan(Uri.parse(it)) } } },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(168.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.uri }) { entry -> VideoCard(app, entry) { onOpen(entry) } }
                }
            }
        }
    }
    if (deviceSheet) DeviceSheet(app, onDismiss = { deviceSheet = false })
}

@Composable
private fun EmptyState(modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.FolderOpen, null, Modifier.size(56.dp), tint = Palette.Accent)
        Spacer(Modifier.height(16.dp))
        Text("Choose the folder with your videos", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Scripts with the same name (video.funscript) are matched automatically – in subfolders too.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPick) { Text("Choose folder") }
    }
}

@Composable
private fun VideoCard(app: FunFlowApp, entry: VideoEntry, onClick: () -> Unit) {
    val thumb by produceState(app.videoInfo.thumbnailFile(entry.uri).takeIf { it.exists() }, entry.uri) {
        value = app.videoInfo.ensure(entry.uri)
    }
    val info = remember(thumb) { app.videoInfo.cachedInfo(entry.uri) }
    val resume = remember(entry.uri) { app.positions.get(entry.uri) }

    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (thumb != null) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info?.is4k == true) Badge("4K")
                if (info?.hdr == true) Badge("HDR")
            }
            if (entry.hasScript) {
                Icon(
                    Icons.Default.GraphicEq, "Has a script",
                    tint = Palette.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color(0xAA000000), CircleShape).padding(3.dp).size(16.dp),
                )
            }
            if (info != null && info.durationMs > 0) {
                Badge(formatTime(info.durationMs), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                if (resume > 0) {
                    LinearProgressIndicator(
                        progress = { (resume.toFloat() / info.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                        color = Palette.Hot,
                        trackColor = Color(0x55000000),
                        drawStopIndicator = {},
                    )
                }
            }
        }
        Text(
            entry.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = modifier.background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
