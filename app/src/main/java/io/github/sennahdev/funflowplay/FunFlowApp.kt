package io.github.sennahdev.funflowplay

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import io.github.sennahdev.funflowplay.data.LibraryRepository
import io.github.sennahdev.funflowplay.data.PlaybackPositions
import io.github.sennahdev.funflowplay.data.SettingsStore
import io.github.sennahdev.funflowplay.data.VideoInfoRepository
import io.github.sennahdev.funflowplay.data.VideoPrefs
import io.github.sennahdev.funflowplay.device.ButtplugClient
import io.github.sennahdev.funflowplay.device.OutputController

/** Tiny manual dependency container - the app is small enough not to need a DI framework. */
class FunFlowApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsStore private set
    lateinit var library: LibraryRepository private set
    lateinit var videoInfo: VideoInfoRepository private set
    lateinit var positions: PlaybackPositions private set
    lateinit var videoPrefs: VideoPrefs private set
    lateinit var buttplug: ButtplugClient private set
    lateinit var output: OutputController private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        library = LibraryRepository(this)
        videoInfo = VideoInfoRepository(this)
        positions = PlaybackPositions(this)
        videoPrefs = VideoPrefs(this)
        buttplug = ButtplugClient(appScope)
        output = OutputController(buttplug)
        if (settings.value.autoConnect) buttplug.connect(settings.value.serverUrl)
    }
}
