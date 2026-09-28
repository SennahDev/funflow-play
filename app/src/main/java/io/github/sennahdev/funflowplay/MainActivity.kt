package io.github.sennahdev.funflowplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.sennahdev.funflowplay.data.VideoEntry
import io.github.sennahdev.funflowplay.player.PlayerScreen
import io.github.sennahdev.funflowplay.ui.FunFlowTheme
import io.github.sennahdev.funflowplay.ui.LibraryScreen
import io.github.sennahdev.funflowplay.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FunFlowApp
        setContent {
            FunFlowTheme {
                // Three screens - plain state instead of a navigation library.
                var playingUri by rememberSaveable { mutableStateOf<String?>(null) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val playing: VideoEntry? = playingUri?.let { uri -> app.library.entries.value.firstOrNull { it.uri == uri } }

                when {
                    playing != null -> {
                        BackHandler { playingUri = null }
                        PlayerScreen(app, playing, onClose = { playingUri = null })
                    }
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(app, onBack = { showSettings = false })
                    }
                    else -> LibraryScreen(
                        app,
                        onOpen = { playingUri = it.uri },
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Never leave a toy running in the background.
        (application as FunFlowApp).output.stop()
    }
}
