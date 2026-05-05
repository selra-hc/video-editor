package com.videoeditor

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Encapsulates all [MediaPlayer] and [TextureView] lifecycle management for the video preview.
 *
 * Rationale for [MediaPlayer] over ExoPlayer:
 *  - Uses the same native media stack as [android.media.MediaMetadataRetriever].
 *  - Handles Samsung moov-at-end camera recordings via an O(1) native seek to the moov atom.
 *  - ExoPlayer's Mp4Extractor performed O(N) sequential skips through large mdat boxes before
 *    reaching moov, causing the seekbar to stay greyed-out indefinitely on multi-GB files.
 *
 * Threading contract:
 *  - All public functions must be called from the **main thread**.
 *  - [positionMs] is a [StateFlow] updated from the main thread every [POLL_IDLE_MS] ms
 *    (or [POLL_PLAYING_MS] when playing), safe to collect from any coroutine.
 *
 * @param scope  Lifecycle-bound coroutine scope (typically `Activity.lifecycleScope`).
 */
class MediaPlayerController(
    private val context: Context,
    private val textureView: TextureView,
    private val scope: CoroutineScope,
) {

    // ── State ─────────────────────────────────────────────────────────────────

    private var player: MediaPlayer? = null
    var isPrepared: Boolean = false
        private set

    private var pendingSurface: Surface? = null
    private var positionJob: Job? = null

    /** Memoized inputs to avoid recomputing the texture matrix when nothing changed. */
    private var lastTransformKey: Long = -1L

    // ── Public state exposed to MainActivity ──────────────────────────────────

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** Fired when the player is ready; supplies (displayWidth, displayHeight). */
    var onPrepared: ((Int, Int) -> Unit)? = null

    /** Fired when the video dimensions are (re-)reported by the decoder. */
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null

    /** Fired on [MediaPlayer] error (what, extra). */
    var onError: ((Int, Int) -> Unit)? = null

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG            = "MediaPlayerController"
        private const val POLL_PLAYING_MS = 100L   // 10 Hz while playing
        private const val POLL_IDLE_MS    = 500L   // 2 Hz while paused (battery saver)
    }

    // ── Surface management ────────────────────────────────────────────────────

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                pendingSurface = Surface(st)
                player?.let { mp ->
                    if (isPrepared) {
                        mp.setSurface(pendingSurface)
                        updateTransform()
                    }
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                updateTransform()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                pendingSurface?.release()
                pendingSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Releases any existing player and starts loading [uri] asynchronously.
     * [onPrepared] is called on the main thread once the player is ready.
     */
    fun load(uri: Uri) {
        release()

        Log.d(TAG, "load uri=$uri")
        val mp = MediaPlayer().also { player = it }
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )

        mp.setOnPreparedListener { prepared ->
            isPrepared = true
            pendingSurface?.let { prepared.setSurface(it) }
            try { prepared.start(); prepared.pause(); prepared.seekTo(0) } catch (_: IllegalStateException) {}
            updateTransform()
            _isPlaying.value = false
            startPositionPolling()
            onPrepared?.invoke(prepared.videoWidth, prepared.videoHeight)
        }

        mp.setOnVideoSizeChangedListener { _, w, h ->
            if (w > 0 && h > 0) {
                updateTransform()
                onVideoSizeChanged?.invoke(w, h)
            }
        }

        mp.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            onError?.invoke(what, extra)
            true
        }

        mp.setOnCompletionListener {
            try { it.seekTo(0) } catch (_: IllegalStateException) {}
            _isPlaying.value = false
        }

        try {
            mp.setDataSource(context, uri)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource failed for $uri", e)
            player = null
            mp.release()
            throw e
        }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun togglePlayPause() {
        val mp = player?.takeIf { isPrepared } ?: return
        try {
            if (mp.isPlaying) { mp.pause(); _isPlaying.value = false }
            else              { mp.start(); _isPlaying.value = true  }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "togglePlayPause: ${e.message}")
        }
    }

    fun seekTo(ms: Long) {
        val mp = player?.takeIf { isPrepared } ?: return
        try { mp.seekTo(ms.toInt()) } catch (e: IllegalStateException) {
            Log.w(TAG, "seekTo: ${e.message}")
        }
    }

    fun skipBy(deltaMs: Long, durationMs: Long) {
        val mp = player?.takeIf { isPrepared } ?: return
        try {
            val newPos = (mp.currentPosition.toLong() + deltaMs).coerceIn(0L, durationMs)
            mp.seekTo(newPos.toInt())
        } catch (e: IllegalStateException) {
            Log.w(TAG, "skipBy: ${e.message}")
        }
    }

    fun pause() {
        if (isPrepared) try { player?.pause() } catch (_: IllegalStateException) {}
        _isPlaying.value = false
    }

    fun currentPositionMs(): Long =
        try { player?.takeIf { isPrepared }?.currentPosition?.toLong() ?: 0L }
        catch (_: IllegalStateException) { 0L }

    // ── Release ───────────────────────────────────────────────────────────────

    /**
     * Fully releases the [MediaPlayer] and stops the polling coroutine.
     * After this call the controller is ready to [load] a new URI.
     *
     * Note: `MediaPlayer.release()` is **synchronous** and guarantees that the hardware codec
     * is freed before returning — important before Transformer acquires the same decoder.
     */
    fun release() {
        positionJob?.cancel()
        positionJob = null
        isPrepared = false
        _isPlaying.value = false
        try { player?.reset() } catch (_: Exception) {}
        player?.release()
        player = null
    }

    // ── Texture transform ─────────────────────────────────────────────────────

    /**
     * Applies a letterbox/pillarbox [Matrix] to [textureView] so that the video is displayed at
     * its correct aspect ratio within the view's bounds.
     *
     * The same min-scale formula is used in [CropOverlayView.recalcVideoRect] so that crop
     * coordinates and the preview are always perfectly aligned.
     *
     * Memoized on the combination of (textureW, textureH, videoW, videoH) to avoid redundant
     * matrix computations during rapid layout/size callbacks.
     */
    fun updateTransform(videoW: Int = 0, videoH: Int = 0) {
        textureView.post {
            val tW = textureView.width.takeIf  { it > 0 } ?: return@post
            val tH = textureView.height.takeIf { it > 0 } ?: return@post
            val vW = videoW.takeIf { it > 0 }
                ?: player?.takeIf { isPrepared }?.videoWidth?.takeIf { it > 0 }
                ?: return@post
            val vH = videoH.takeIf { it > 0 }
                ?: player?.takeIf { isPrepared }?.videoHeight?.takeIf { it > 0 }
                ?: return@post

            // Pack the four dimensions into a single Long for cheap memoization.
            val key = (tW.toLong() shl 48) or (tH.toLong() shl 32) or
                      (vW.toLong() shl 16) or vH.toLong()
            if (key == lastTransformKey) return@post
            lastTransformKey = key

            val videoAspect = vW.toFloat() / vH
            val viewAspect  = tW.toFloat() / tH
            val matrix = Matrix()
            when {
                videoAspect > viewAspect ->
                    matrix.setScale(1f, viewAspect / videoAspect, tW / 2f, tH / 2f)
                videoAspect < viewAspect ->
                    matrix.setScale(videoAspect / viewAspect, 1f, tW / 2f, tH / 2f)
            }
            textureView.setTransform(matrix)
        }
    }

    // ── Position polling ──────────────────────────────────────────────────────

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val mp = player
                if (mp != null && isPrepared) {
                    try {
                        _positionMs.value = mp.currentPosition.toLong()
                        _isPlaying.value  = mp.isPlaying
                    } catch (_: IllegalStateException) { /* transient state */ }
                }
                delay(if (_isPlaying.value) POLL_PLAYING_MS else POLL_IDLE_MS)
            }
        }
    }
}
