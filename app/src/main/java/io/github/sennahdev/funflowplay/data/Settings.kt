package io.github.sennahdev.funflowplay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SortOrder { NAME, NEWEST }

/** All user settings. Kept small on purpose - every field has a sensible default. */
data class Settings(
    // device
    val serverUrl: String = "ws://127.0.0.1:12345",
    val autoConnect: Boolean = true,
    val minPercent: Int = 22,        // device level for the smallest non-zero value (below it the toy doesn't move)
    val maxPercent: Int = 100,       // device level for 100 %
    val latencyMs: Int = 0,          // positive = device gets values earlier
    // stroke scripts (converted to speed)
    val strokeFullSpeed: Int = 350,  // units/s that mean 100 %
    val strokeSmoothingMs: Int = 700,
    // playback / library
    val skipSeconds: Int = 10,
    val onlyWithScripts: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NAME,
    val libraryTreeUri: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            serverUrl = prefs.getString("serverUrl", d.serverUrl) ?: d.serverUrl,
            autoConnect = prefs.getBoolean("autoConnect", d.autoConnect),
            minPercent = prefs.getInt("minPercent", d.minPercent),
            maxPercent = prefs.getInt("maxPercent", d.maxPercent),
            latencyMs = prefs.getInt("latencyMs", d.latencyMs),
            strokeFullSpeed = prefs.getInt("strokeFullSpeed", d.strokeFullSpeed),
            strokeSmoothingMs = prefs.getInt("strokeSmoothingMs", d.strokeSmoothingMs),
            skipSeconds = prefs.getInt("skipSeconds", d.skipSeconds),
            onlyWithScripts = prefs.getBoolean("onlyWithScripts", d.onlyWithScripts),
            sortOrder = runCatching { SortOrder.valueOf(prefs.getString("sortOrder", null)!!) }.getOrDefault(d.sortOrder),
            libraryTreeUri = prefs.getString("libraryTreeUri", null),
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putString("serverUrl", s.serverUrl)
            .putBoolean("autoConnect", s.autoConnect)
            .putInt("minPercent", s.minPercent)
            .putInt("maxPercent", s.maxPercent)
            .putInt("latencyMs", s.latencyMs)
            .putInt("strokeFullSpeed", s.strokeFullSpeed)
            .putInt("strokeSmoothingMs", s.strokeSmoothingMs)
            .putInt("skipSeconds", s.skipSeconds)
            .putBoolean("onlyWithScripts", s.onlyWithScripts)
            .putString("sortOrder", s.sortOrder.name)
            .putString("libraryTreeUri", s.libraryTreeUri)
            .apply()
    }
}
