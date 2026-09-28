package io.github.sennahdev.funflowplay.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EngineState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val ended: Boolean = false,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val error: String? = null,
    /** Dolby Vision profile 5 without a DV decoder: colors would be wrong (pink/green). */
    val needsFallback: Boolean = false,
    val formatInfo: String = "",
)

/** A video player backend. The UI and the sync loop only use this interface. */
interface VideoEngine {
    val name: String
    val state: StateFlow<EngineState>
    fun positionMs(): Long
    fun play()
    fun pause()
    /** fast = nearest keyframe (scrubbing), else frame exact. */
    fun seekTo(ms: Long, fast: Boolean = false)
    fun release()

    @Composable
    fun Surface(modifier: Modifier)
}

/**
 * Android's own player (Media3 ExoPlayer) rendering straight into a SurfaceView:
 * hardware decoding, zero copies, HDR10/HLG passed to the display as HDR. Best
 * performance and battery life - the default for everything it can show correctly.
 */
@UnstableApi
class ExoEngine(context: Context, uri: Uri, startMs: Long) : VideoEngine {
    override val name = "System (ExoPlayer)"
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = update { it.copy(playing = isPlaying) }

            override fun onPlaybackStateChanged(playbackState: Int) = update {
                it.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    ended = playbackState == Player.STATE_ENDED,
                    durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: it.durationMs,
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = update {
                it.copy(width = videoSize.width, height = videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "playback error", error)
                update { it.copy(error = error.errorCodeName) }
            }

            override fun onTracksChanged(tracks: Tracks) = checkVideoFormat(tracks)
        })
        player.setMediaItem(MediaItem.fromUri(uri), startMs)
        player.prepare()
        player.playWhenReady = true
    }

    private fun update(f: (EngineState) -> EngineState) {
        _state.value = f(_state.value)
    }

    private fun checkVideoFormat(tracks: Tracks) {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO || !group.isSelected) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val f = group.getTrackFormat(i)
                val codecs = f.codecs ?: ""
                val dv = f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || codecs.startsWith("dvh") || codecs.startsWith("dva")
                val profile = if (dv) codecs.split('.').getOrNull(1)?.toIntOrNull() else null
                val hasDvDecoder = runCatching {
                    MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty()
                }.getOrDefault(false)
                val transfer = when (f.colorInfo?.colorTransfer) {
                    C.COLOR_TRANSFER_ST2084 -> "HDR10"
                    C.COLOR_TRANSFER_HLG -> "HLG"
                    else -> "SDR"
                }
                val info = buildString {
                    append("${f.width}x${f.height} ")
                    append(f.sampleMimeType?.substringAfter('/') ?: "?")
                    if (dv) append(" · Dolby Vision P${profile ?: "?"}") else append(" · $transfer")
                }
                // Profile 5 has no HDR10/SDR base layer: without a DV decoder the colors are wrong.
                val fallback = dv && profile == 5 && !hasDvDecoder
                update { it.copy(formatInfo = info, needsFallback = fallback) }
                return
            }
        }
    }

    override fun positionMs(): Long = player.currentPosition
    override fun play() = player.play()
    override fun pause() = player.pause()

    override fun seekTo(ms: Long, fast: Boolean) {
        player.setSeekParameters(if (fast) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        player.seekTo(ms.coerceAtLeast(0))
    }

    override fun release() = player.release()

    @Composable
    override fun Surface(modifier: Modifier) {
        ContentFrame(
            player = player,
            modifier = modifier,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
        )
    }

    private companion object {
        const val TAG = "ExoEngine"
    }
}
