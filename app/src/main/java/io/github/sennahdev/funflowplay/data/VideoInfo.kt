package io.github.sennahdev.funflowplay.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Facts about a video file (read once, cached on disk). */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hdr: Boolean,
) {
    val is4k: Boolean get() = maxOf(width, height) >= 3200
}

/**
 * Thumbnails + video info, generated in the background with at most 2 parallel
 * decoders (4K decoding is expensive) and cached in the app's cache folder.
 */
class VideoInfoRepository(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, "thumbs").apply { mkdirs() }
    private val infoFile = File(app.filesDir, "videoinfo.json")
    private val infos = HashMap<String, VideoInfo>()
    private val lock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoders = Dispatchers.IO.limitedParallelism(2)

    init {
        runCatching {
            val o = JSONObject(infoFile.readText())
            for (k in o.keys()) {
                val v = o.getJSONObject(k)
                infos[k] = VideoInfo(v.getLong("d"), v.getInt("w"), v.getInt("h"), v.getBoolean("hdr"))
            }
        }
    }

    fun cachedInfo(uri: String): VideoInfo? = synchronized(infos) { infos[key(uri)] }

    fun thumbnailFile(uri: String): File = File(dir, key(uri) + ".jpg")

    /** Make sure thumbnail + info exist. Returns the thumbnail file (or null if the video can't be read). */
    suspend fun ensure(uri: String): File? = withContext(decoders) {
        val thumb = thumbnailFile(uri)
        if (thumb.exists() && cachedInfo(uri) != null) return@withContext thumb
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app, Uri.parse(uri))
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val transfer = if (Build.VERSION.SDK_INT >= 30) {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
            } else null
            val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
            val info = VideoInfo(duration, w, h, hdr)
            // A frame from 15 % in avoids black intros.
            val frame = r.getScaledFrameAtTime(duration * 150L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
            if (frame != null) {
                thumb.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                frame.recycle()
            }
            lock.withLock {
                synchronized(infos) { infos[key(uri)] = info }
                saveInfos()
            }
            if (thumb.exists()) thumb else null
        } catch (e: Exception) {
            Log.w("VideoInfo", "cannot read $uri: ${e.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun clearThumbnails() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun saveInfos() {
        val o = JSONObject()
        synchronized(infos) {
            for ((k, v) in infos) {
                o.put(k, JSONObject().put("d", v.durationMs).put("w", v.width).put("h", v.height).put("hdr", v.hdr))
            }
        }
        runCatching { infoFile.writeText(o.toString()) }
    }

    private fun key(uri: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(20)
    }
}

/** Remembers where each video was left. */
class PlaybackPositions(context: Context) {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun get(uri: String): Long = prefs.getLong(uri, 0L)

    fun set(uri: String, ms: Long, durationMs: Long) {
        // Finished (or barely started) videos start from the beginning next time.
        val value = if (ms < 5_000 || (durationMs > 0 && ms > durationMs - 10_000)) 0L else ms
        prefs.edit().putLong(uri, value).apply()
    }
}

/** Per-video choices: script kind override. */
class VideoPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("video_prefs", Context.MODE_PRIVATE)

    fun scriptKind(uri: String): String? = prefs.getString("kind:$uri", null)
    fun setScriptKind(uri: String, kind: String?) = prefs.edit().apply {
        if (kind == null) remove("kind:$uri") else putString("kind:$uri", kind)
    }.apply()
}
