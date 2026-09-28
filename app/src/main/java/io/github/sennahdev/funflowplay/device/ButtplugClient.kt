package io.github.sennahdev.funflowplay.device

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Output of a device feature we can drive with an intensity (0..maxStep). */
data class DeviceOutput(val featureIndex: Int, val type: String, val maxStep: Int)

data class Device(
    val index: Int,
    val name: String,
    val messageGapMs: Int,
    val outputs: List<DeviceOutput>,
)

sealed interface Connection {
    data object Disconnected : Connection
    data object Connecting : Connection
    data class Connected(val serverName: String) : Connection
}

/**
 * Minimal client for the Buttplug protocol v4 (Intiface Central 3.x) over a websocket.
 * Only what a player needs: handshake, device list, scanning, intensity outputs, stop.
 */
class ButtplugClient(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    private val ids = AtomicInteger(1)
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var wantConnected = false
    @Volatile private var url = ""

    private val _connection = MutableStateFlow<Connection>(Connection.Disconnected)
    val connection: StateFlow<Connection> = _connection.asStateFlow()
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Connect and keep reconnecting until [disconnect] (Intiface may start later). */
    fun connect(serverUrl: String) {
        url = serverUrl
        wantConnected = true
        if (_connection.value == Connection.Disconnected) open()
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        stopAll()
        socket?.close(1000, null)
        socket = null
        _connection.value = Connection.Disconnected
        _devices.value = emptyList()
        _scanning.value = false
    }

    fun startScan() = send("StartScanning", JSONObject()).also { _scanning.value = true }
    fun stopScan() = send("StopScanning", JSONObject()).also { _scanning.value = false }

    fun output(device: Int, output: DeviceOutput, step: Int) {
        send(
            "OutputCmd",
            JSONObject()
                .put("DeviceIndex", device)
                .put("FeatureIndex", output.featureIndex)
                .put("Command", JSONObject().put(output.type, JSONObject().put("Value", step))),
        )
    }

    fun stopAll() {
        send("StopCmd", JSONObject().put("Inputs", true).put("Outputs", true))
    }

    // ------------------------------------------------------------------ internals
    private fun open() {
        _connection.value = Connection.Connecting
        _error.value = null
        val request = runCatching { Request.Builder().url(url).build() }.getOrElse {
            _error.value = "Invalid address: $url"
            _connection.value = Connection.Disconnected
            return
        }
        socket = http.newWebSocket(request, Listener())
    }

    private fun send(type: String, body: JSONObject): Boolean {
        val ws = socket ?: return false
        if (_connection.value !is Connection.Connected && type != "RequestServerInfo") return false
        body.put("Id", ids.getAndIncrement())
        return ws.send(JSONArray().put(JSONObject().put(type, body)).toString())
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            if (wantConnected && _connection.value == Connection.Disconnected) open()
        }
    }

    private fun handle(type: String, body: JSONObject) {
        when (type) {
            "ServerInfo" -> {
                _connection.value = Connection.Connected(body.optString("ServerName", "Intiface"))
                val maxPing = body.optInt("MaxPingTime", 0)
                pingJob?.cancel()
                if (maxPing > 0) {
                    pingJob = scope.launch {
                        while (isActive) {
                            send("Ping", JSONObject())
                            delay(maxPing / 2L)
                        }
                    }
                }
                send("RequestDeviceList", JSONObject())
            }
            "DeviceList" -> _devices.value = parseDevices(body.optJSONObject("Devices"))
            "ScanningFinished" -> _scanning.value = false
            "Error" -> {
                val msg = body.optString("ErrorMessage")
                Log.w(TAG, "server error: $msg")
                _error.value = msg
            }
        }
    }

    private fun parseDevices(obj: JSONObject?): List<Device> {
        if (obj == null) return emptyList()
        val out = mutableListOf<Device>()
        for (key in obj.keys()) {
            val d = obj.getJSONObject(key)
            val outputs = mutableListOf<DeviceOutput>()
            val features = d.optJSONObject("DeviceFeatures")
            if (features != null) {
                for (fk in features.keys()) {
                    val f = features.getJSONObject(fk)
                    val o = f.optJSONObject("Output") ?: continue
                    for (type in o.keys()) {
                        if (type !in INTENSITY_TYPES) continue
                        val range = o.getJSONObject(type).optJSONArray("Value") ?: continue
                        outputs += DeviceOutput(f.optInt("FeatureIndex", fk.toInt()), type, range.optInt(1, 0))
                    }
                }
            }
            val display = d.optString("DeviceDisplayName").takeIf { it.isNotEmpty() && it != "null" }
            out += Device(d.getInt("DeviceIndex"), display ?: d.optString("DeviceName"), d.optInt("DeviceMessageTimingGap", 0), outputs)
        }
        return out.sortedBy { it.index }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send(
                "RequestServerInfo",
                JSONObject().put("ClientName", "FunFlow Play").put("ProtocolVersionMajor", 4).put("ProtocolVersionMinor", 0),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val msg = arr.getJSONObject(i)
                    val type = msg.keys().next()
                    handle(type, msg.getJSONObject(type))
                }
            }.onFailure { Log.w(TAG, "bad message: $text", it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        private fun lost(webSocket: WebSocket) {
            if (webSocket !== socket) return
            socket = null
            pingJob?.cancel()
            _connection.value = Connection.Disconnected
            _devices.value = emptyList()
            _scanning.value = false
            scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "Buttplug"
        val INTENSITY_TYPES = setOf("Vibrate", "Oscillate", "Constrict")
    }
}
