package com.videoeditor

import android.graphics.Bitmap
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.videoeditor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var player: ExoPlayer? = null
    private var playerPfd: ParcelFileDescriptor? = null  // kept open while ExoPlayer uses the file
    private var videoFilePath: String? = null             // real path for file:// URI, if readable
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0L

    // ── Video metadata ────────────────────────────────────────────────────────

    private var originalRotationDeg: Int = 0
    private var videoDisplayW: Int = 0
    private var videoDisplayH: Int = 0

    // ── Rotation state ────────────────────────────────────────────────────────

    private var rotationDeltaDeg: Int = 0
    private var suppressRotationWatcher = false
    private var suppressTextWatcher = false

    // ── Processing ────────────────────────────────────────────────────────────

    private var positionJob: Job? = null
    private var metadataJob: Job? = null   // IO coroutine that reads metadata + thumbnails
    private var activeTransformer: Transformer? = null

    // ── Activity result launchers ─────────────────────────────────────────────

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchVideoPicker()
            else Toast.makeText(this, "Storage permission is required", Toast.LENGTH_LONG).show()
        }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { loadVideo(it) }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupListeners()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_select_video) {
            requestVideoPermission()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        positionJob?.cancel()
        metadataJob?.cancel()
        player?.release()
        player = null
        playerPfd?.close()
        playerPfd = null
        activeTransformer?.cancel()
        activeTransformer = null
    }

    // ── UI wiring ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        // Tap on empty preview area to select video
        binding.noVideoPlaceholder.setOnClickListener { requestVideoPermission() }

        // Apply changes
        binding.btnCut.setOnClickListener { processVideo() }

        // ── Toggle buttons ────────────────────────────────────────────────────
        binding.btnToggleTrim.addOnCheckedChangeListener { _, isChecked ->
            binding.trimSection.isVisible = isChecked
        }
        binding.btnToggleRotation.addOnCheckedChangeListener { _, isChecked ->
            binding.rotationSection.isVisible = isChecked
        }
        binding.btnToggleCrop.addOnCheckedChangeListener { _, isChecked ->
            binding.cropSection.isVisible = isChecked
            binding.cropOverlayView.setOverlayEnabled(isChecked)
            if (isChecked) refreshCropInfo()
        }

        // ── Rotation controls ─────────────────────────────────────────────────
        binding.btnRotateMinus90.setOnClickListener { adjustRotation(-90) }
        binding.btnRotatePlus90.setOnClickListener  { adjustRotation(+90) }

        binding.etRotation.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (suppressRotationWatcher) return
                rotationDeltaDeg = s?.toString()?.toIntOrNull() ?: return
                refreshEffectiveRotationHint()
            }
        })

        // ── Crop overlay callback ─────────────────────────────────────────────
        binding.cropOverlayView.onCropChanged = { _, _, _, _ -> refreshCropInfo() }

        // ── TrimRangeView → text fields + labels ──────────────────────────────
        binding.trimRangeView.onStartTimeChanged = { ms ->
            setTextSilently(binding.etStartTime, formatSec(ms))
            refreshLabels()
        }
        binding.trimRangeView.onEndTimeChanged = { ms ->
            setTextSilently(binding.etEndTime, formatSec(ms))
            refreshLabels()
        }
        binding.trimRangeView.onSeek = { ms -> player?.seekTo(ms) }

        // ── Text fields → TrimRangeView ───────────────────────────────────────
        binding.etStartTime.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                val ms = secInputToMs(s) ?: return
                binding.trimRangeView.setStartTime(ms)
                refreshLabels()
            }
        })
        binding.etEndTime.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                val ms = secInputToMs(s) ?: return
                binding.trimRangeView.setEndTime(ms)
                refreshLabels()
            }
        })
    }

    // ── Permission + video picker ─────────────────────────────────────────────

    private fun requestVideoPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            launchVideoPicker()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun launchVideoPicker() = videoPickerLauncher.launch("video/*")

    // ── Video loading ─────────────────────────────────────────────────────────

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        videoDurationMs = 0L
        videoDisplayW = 0
        videoDisplayH = 0
        binding.noVideoPlaceholder.isVisible = false

        // Reset all tool toggles (auto-fires listeners → hides sections, disables crop)
        binding.btnToggleTrim.isChecked = false
        binding.btnToggleRotation.isChecked = false
        binding.btnToggleCrop.isChecked = false

        // Reset rotation state
        rotationDeltaDeg = 0
        originalRotationDeg = 0
        setRotationTextSilently(0)
        refreshEffectiveRotationHint()

        // Release any previous player / metadata load
        positionJob?.cancel()
        metadataJob?.cancel()
        playerPfd?.close()
        playerPfd = null
        videoFilePath = null
        player?.release()

        // ── ExoPlayer for preview playback only ───────────────────────────────
        // We no longer rely on ExoPlayer to supply the video duration.  For large
        // files (e.g. 9 GB Samsung UHD HEVC) with the moov atom at the end of the
        // file, ExoPlayer can reach STATE_READY while still returning C.TIME_UNSET
        // for the duration, which would leave all tool toggles permanently disabled.
        // Get the real file size via fstat so SeekableContentDataSource can report it
        // to Mp4Extractor (needed to locate the moov atom at the end of large files).
        val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        playerPfd = pfd
        val fileSize = pfd?.let {
            try { Os.fstat(it.fileDescriptor).st_size } catch (_: Exception) { -1L }
        } ?: -1L

        // Preferred: resolve the actual file path.  With READ_MEDIA_VIDEO / READ_EXTERNAL_STORAGE
        // already granted, File(path).canRead() returns true for DCIM/Camera files, and
        // ExoPlayer's FileDataSource uses RandomAccessFile — O(1) seeking at any file size.
        val filePath = runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
        videoFilePath = filePath?.takeIf { File(it).canRead() }

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        binding.playerView.player = exo
        val mediaItem = MediaItem.fromUri(uri)
        when {
            videoFilePath != null -> {
                // file:// URI → FileDataSource → RandomAccessFile: true 64-bit random
                // access for files of any size, including 15 GB HEVC recordings.
                exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoFilePath!!))))
            }
            fileSize > 0 -> {
                // Fallback: SeekableContentDataSource uses openFileDescriptor() (regular-file
                // fd, avoids the pipe fd that openTypedAssetFileDescriptor can return on
                // Samsung for large files) + Os.lseek for O(1) seeks.
                val factory = DataSource.Factory {
                    SeekableContentDataSource(contentResolver, uri, fileSize)
                }
                exo.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem))
            }
            else -> exo.setMediaItem(mediaItem)
        }
        exo.prepare()
        exo.playWhenReady = false

        exo.addListener(object : Player.Listener {
            // Only used for the crop overlay: ExoPlayer reports display-oriented size
            // as soon as the first decoded frame is available.
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoDisplayW = videoSize.width
                    videoDisplayH = videoSize.height
                    binding.cropOverlayView.setVideoInfo(videoDisplayW, videoDisplayH)
                }
            }
        })

        startPositionUpdater()

        // ── Primary metadata source: MediaMetadataRetriever (IO thread) ───────
        // MediaMetadataRetriever reads directly from the file descriptor, handles
        // moov-at-end MP4 containers, and returns the correct duration regardless
        // of ExoPlayer's parsing state.  We also generate thumbnails here so we
        // only open the (potentially 9 GB) file once.
        metadataJob = lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, uri)

                val dur  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                              ?.toLongOrNull() ?: 0L
                val rot  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                              ?.toIntOrNull() ?: 0
                val encW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                              ?.toIntOrNull() ?: 0
                val encH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                              ?.toIntOrNull() ?: 0

                if (dur <= 0L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not read video metadata — unsupported format?",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }

                // Display dimensions account for the rotation hint
                val dispW = if (rot == 90 || rot == 270) encH else encW
                val dispH = if (rot == 90 || rot == 270) encW else encH

                withContext(Dispatchers.Main) {
                    videoDurationMs    = dur
                    originalRotationDeg = rot
                    if (dispW > 0 && dispH > 0) {
                        videoDisplayW = dispW
                        videoDisplayH = dispH
                        binding.cropOverlayView.setVideoInfo(dispW, dispH)
                    }
                    onVideoMetadataReady()
                }

                // Generate thumbnails in the same coroutine — retriever is already open
                val count  = 12
                val thumbW = 160
                val thumbH = 90
                val bitmaps = (0 until count).mapNotNull { i ->
                    val timeUs = dur * 1_000L * i / count
                    @Suppress("DEPRECATION")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, thumbW, thumbH,
                        )
                    } else {
                        val bmp = retriever.getFrameAtTime(
                            timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        ) ?: return@mapNotNull null
                        val scaled = Bitmap.createScaledBitmap(bmp, thumbW, thumbH, true)
                        if (scaled !== bmp) bmp.recycle()
                        scaled
                    }
                }
                withContext(Dispatchers.Main) { binding.trimRangeView.setThumbnails(bitmaps) }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not open video: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                retriever.release()
            }
        }
    }

    /**
     * Called on the main thread once video metadata (duration, rotation, dimensions) is
     * available from [MediaMetadataRetriever].  Initialises the UI and enables controls.
     */
    private fun onVideoMetadataReady() {
        binding.trimRangeView.setDuration(videoDurationMs)
        binding.trimRangeView.setStartTime(0L)
        binding.trimRangeView.setEndTime(videoDurationMs)
        setTextSilently(binding.etStartTime, "0.00")
        setTextSilently(binding.etEndTime, formatSec(videoDurationMs))
        refreshLabels()

        binding.btnCut.isEnabled = true
        binding.btnToggleTrim.isEnabled = true
        binding.btnToggleRotation.isEnabled = true
        binding.btnToggleCrop.isEnabled = true
    }

    // ── Playback position updater ─────────────────────────────────────────────

    private fun startPositionUpdater() {
        positionJob?.cancel()
        positionJob = lifecycleScope.launch {
            while (true) {
                player?.let { binding.trimRangeView.setCurrentTime(it.currentPosition) }
                delay(33L)
            }
        }
    }

    // ── Rotation helpers ──────────────────────────────────────────────────────

    private fun adjustRotation(delta: Int) {
        rotationDeltaDeg = (rotationDeltaDeg + delta).let {
            if (it >= 360) it - 360 else if (it <= -360) it + 360 else it
        }
        setRotationTextSilently(rotationDeltaDeg)
        refreshEffectiveRotationHint()
    }

    private fun setRotationTextSilently(value: Int) {
        suppressRotationWatcher = true
        binding.etRotation.setText(value.toString())
        suppressRotationWatcher = false
    }

    private fun refreshEffectiveRotationHint() {
        val effective = (originalRotationDeg + rotationDeltaDeg).mod(360)
        if (rotationDeltaDeg != 0 && effective % 90 != 0) {
            val snapped = VideoTrimmer.snapToValidHint(effective)
            binding.tvEffectiveRotation.text = getString(R.string.effective_rotation, snapped)
            binding.tvEffectiveRotation.isVisible = true
        } else {
            binding.tvEffectiveRotation.isVisible = false
        }
    }

    // ── Crop helpers ──────────────────────────────────────────────────────────

    private fun refreshCropInfo() {
        if (!binding.btnToggleCrop.isChecked) {
            binding.tvCropInfo.isVisible = false
            return
        }
        val rect = binding.cropOverlayView.getCropRectInVideoPixels() ?: return
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        binding.tvCropInfo.text = getString(R.string.crop_info, w, h, rect.left, rect.top)
        binding.tvCropInfo.isVisible = true
    }

    // ── Video processing ──────────────────────────────────────────────────────

    private fun processVideo() {
        val uri = videoUri ?: run {
            Toast.makeText(this, getString(R.string.error_no_video), Toast.LENGTH_SHORT).show()
            return
        }

        // Read active tool states
        val trimEnabled     = binding.btnToggleTrim.isChecked
        val rotationEnabled = binding.btnToggleRotation.isChecked
        val cropEnabled     = binding.btnToggleCrop.isChecked

        val startMs  = if (trimEnabled)     binding.trimRangeView.getStartTime() else 0L
        val endMs    = if (trimEnabled)     binding.trimRangeView.getEndTime()   else videoDurationMs
        val rotDelta = if (rotationEnabled) rotationDeltaDeg                     else 0
        val cropPx   = if (cropEnabled)     binding.cropOverlayView.getCropRectInVideoPixels() else null

        if (endMs - startMs < 100L) {
            Toast.makeText(this, getString(R.string.error_selection_too_short), Toast.LENGTH_SHORT).show()
            return
        }

        val effectiveRot    = (originalRotationDeg + rotDelta).mod(360)
        val isLosslessRot   = effectiveRot % 90 == 0

        if (cropPx == null && isLosslessRot) {
            startLosslessProcess(uri, startMs, endMs, rotDelta)
        } else {
            startTransformerProcess(uri, startMs, endMs, cropPx, rotDelta)
        }
    }

    private fun startLosslessProcess(uri: Uri, startMs: Long, endMs: Long, rotDelta: Int) {
        showProgress(getString(R.string.cutting_video))
        lifecycleScope.launch(Dispatchers.IO) {
            // Use external cache dir (main storage partition) so large UHD files fit.
            // Fall back to internal cacheDir only if external is unavailable.
            val outputDir = externalCacheDir ?: cacheDir
            val cacheOut  = File(outputDir, "cut_${System.currentTimeMillis()}.mp4")
            try {
                // Warn about the 4 GB MediaMuxer limit on API < 29.
                // (On API 29+ the MP4 container can exceed 4 GB.)
                val estimatedOutputBytes = estimateOutputSize(startMs, endMs)
                if (estimatedOutputBytes > 4L * 1024 * 1024 * 1024 &&
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                ) {
                    withContext(Dispatchers.Main) {
                        hideProgress()
                        Toast.makeText(
                            this@MainActivity,
                            "Output exceeds 4 GB limit on Android 9 and older. " +
                                "Trim to a shorter clip or upgrade to Android 10+.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }

                VideoTrimmer.trim(
                    context       = this@MainActivity,
                    inputUri      = uri,
                    outputFile    = cacheOut,
                    startMs       = startMs,
                    endMs         = endMs,
                    rotationDelta = rotDelta,
                    onProgress    = { pct -> runOnUiThread { updateProgress(pct) } },
                )
                val savedUri = saveToMediaStore(cacheOut)
                withContext(Dispatchers.Main) { hideProgress(); showSaveResult(savedUri) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    Toast.makeText(
                        this@MainActivity,
                        buildErrorMessage(e),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                cacheOut.delete()
            }
        }
    }

    private fun startTransformerProcess(
        uri: Uri,
        startMs: Long,
        endMs: Long,
        cropPixels: android.graphics.Rect?,
        rotDelta: Int,
    ) {
        showProgress(getString(R.string.processing_video))
        val outputDir = externalCacheDir ?: cacheDir
        val cacheOut  = File(outputDir, "proc_${System.currentTimeMillis()}.mp4")

        // Fully release ExoPlayer so the hardware HEVC decoder is freed *synchronously*
        // before Transformer tries to acquire it.  stop()+clearMediaItems() only
        // schedules an async release; release() is synchronous and guarantees the
        // codec is available when Transformer initialises its asset loader.
        val resumePosition = player?.currentPosition ?: 0L
        binding.playerView.player = null
        player?.release()
        player = null
        playerPfd?.close()
        playerPfd = null

        // Rebuild ExoPlayer preview after Transformer finishes (success or failure).
        fun restorePlayer() {
            val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            playerPfd = pfd
            val fileSize = pfd?.let {
                try { Os.fstat(it.fileDescriptor).st_size } catch (_: Exception) { -1L }
            } ?: -1L
            val exo = ExoPlayer.Builder(this).build().also { player = it }
            binding.playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoDisplayW = videoSize.width
                        videoDisplayH = videoSize.height
                        binding.cropOverlayView.setVideoInfo(videoDisplayW, videoDisplayH)
                    }
                }
            })
            val mediaItem = MediaItem.fromUri(uri)
            when {
                videoFilePath != null -> {
                    exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoFilePath!!))))
                }
                fileSize > 0 -> {
                    val factory = DataSource.Factory {
                        SeekableContentDataSource(contentResolver, uri, fileSize)
                    }
                    exo.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem))
                }
                else -> exo.setMediaItem(mediaItem)
            }
            exo.seekTo(resumePosition)
            exo.prepare()
            exo.playWhenReady = false
        }

        activeTransformer = VideoProcessor.process(
            context          = this,
            inputUri         = uri,
            outputFile       = cacheOut,
            startMs          = startMs,
            endMs            = endMs,
            cropPixels       = cropPixels,
            videoDisplayW    = videoDisplayW,
            videoDisplayH    = videoDisplayH,
            rotationDeltaDeg = rotDelta,
            onSuccess = {
                lifecycleScope.launch(Dispatchers.IO) {
                    val savedUri = saveToMediaStore(cacheOut)
                    cacheOut.delete()
                    withContext(Dispatchers.Main) {
                        activeTransformer = null
                        hideProgress()
                        restorePlayer()
                        showSaveResult(savedUri)
                    }
                }
            },
            onFailure = { e ->
                cacheOut.delete()
                activeTransformer = null
                hideProgress()
                restorePlayer()
                Toast.makeText(this, buildErrorMessage(e), Toast.LENGTH_LONG).show()
            },
        )
        pollTransformerProgress()
    }

    // ── MediaStore helper ─────────────────────────────────────────────────────

    private suspend fun saveToMediaStore(file: File): Uri? = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) { null }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showProgress(label: String) {
        binding.tvProgressLabel.text = label
        binding.progressBar.isIndeterminate = true
        binding.tvProgressPercent.isVisible = false
        binding.progressLayout.isVisible = true
        binding.btnCut.isEnabled = false
        VideoProcessingService.startProcessing(this, label)
    }

    private fun updateProgress(percent: Int) {
        if (binding.progressBar.isIndeterminate) {
            binding.progressBar.isIndeterminate = false
        }
        binding.progressBar.setProgressCompat(percent, true)
        binding.tvProgressPercent.text = "$percent%"
        binding.tvProgressPercent.isVisible = true
        VideoProcessingService.updateProgress(this, percent, binding.tvProgressLabel.text.toString())
    }

    private fun hideProgress() {
        binding.progressLayout.isVisible = false
        binding.btnCut.isEnabled = true
        VideoProcessingService.stopProcessing(this)
    }

    /**
     * Polls [Transformer.getProgress] every 500 ms while a transformation is active and
     * forwards the result to [updateProgress].  The loop exits automatically once
     * [activeTransformer] becomes null (set to null by success/failure callbacks).
     */
    private fun pollTransformerProgress() {
        lifecycleScope.launch(Dispatchers.Main) {
            val holder = ProgressHolder()
            while (true) {
                delay(500)
                val transformer = activeTransformer ?: break
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    updateProgress(holder.progress)
                }
            }
        }
    }

    private fun showSaveResult(savedUri: Uri?) {
        Toast.makeText(
            this,
            if (savedUri != null) getString(R.string.saved_ok) else "Saved but could not write to gallery",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun setTextSilently(
        field: com.google.android.material.textfield.TextInputEditText,
        text: String,
    ) {
        if (field.text?.toString() == text) return
        suppressTextWatcher = true
        field.setText(text)
        suppressTextWatcher = false
    }

    private fun refreshLabels() {
        val start = binding.trimRangeView.getStartTime()
        val end   = binding.trimRangeView.getEndTime()
        binding.tvStartDisplay.text    = "Start: ${formatSec(start)} s"
        binding.tvEndDisplay.text      = "End: ${formatSec(end)} s"
        binding.tvDurationDisplay.text = "Cut: ${formatSec(end - start)} s"
    }

    private fun formatSec(ms: Long) = String.format("%.2f", ms / 1_000.0)

    private fun secInputToMs(s: Editable?): Long? {
        val sec = s?.toString()?.toDoubleOrNull() ?: return null
        return (sec * 1_000.0).toLong().coerceIn(0L, videoDurationMs)
    }

    /**
     * Rough estimate of output file size for the selected time range, based on the
     * source file size proportionally scaled to the clip duration.  Used to warn
     * about the 4 GB MediaMuxer limit before starting a long operation.
     */
    private fun estimateOutputSize(startMs: Long, endMs: Long): Long {
        if (videoDurationMs <= 0L) return 0L
        val sourceSizeBytes = try {
            contentResolver.openFileDescriptor(videoUri ?: return 0L, "r")
                ?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }
        val ratio = (endMs - startMs).toDouble() / videoDurationMs.toDouble()
        return (sourceSizeBytes * ratio).toLong()
    }

    /**
     * Turns a raw exception into a user-readable error message, flagging common
     * root causes that occur specifically with large or unusual video files.
     */
    private fun buildErrorMessage(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("ENOMEM", ignoreCase = true) ||
            msg.contains("OutOfMemory", ignoreCase = true) ->
                "Not enough memory to process this video. Try trimming to a shorter clip."
            msg.contains("ENOSPC", ignoreCase = true) ||
            msg.contains("No space", ignoreCase = true) ->
                "Not enough storage space for the output file. Free up space and try again."
            msg.contains("ETIMEDOUT", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ->
                "Operation timed out — the video may be too large or the device too slow."
            msg.contains("ERROR_IO", ignoreCase = true) ->
                "Could not read the video file. Make sure storage access is granted."
            else -> "Processing failed: $msg"
        }
    }

    abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }
}
