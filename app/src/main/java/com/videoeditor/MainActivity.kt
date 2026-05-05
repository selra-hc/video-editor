package com.videoeditor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.videoeditor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ── Controllers / pipeline ────────────────────────────────────────────────

    private lateinit var player: MediaPlayerController
    private lateinit var pipeline: ProcessingPipeline

    // ── Video state ───────────────────────────────────────────────────────────

    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private var originalRotationDeg: Int = 0
    private var videoDisplayW: Int = 0
    private var videoDisplayH: Int = 0

    // ── Rotation ──────────────────────────────────────────────────────────────

    private var rotationDeltaDeg: Int = 0
    private var suppressRotationWatcher = false
    private var suppressTextWatcher = false

    // ── Background jobs ───────────────────────────────────────────────────────

    private var metadataJob: Job? = null
    private var progressPollingJob: Job? = null

    companion object { private const val TAG = "VideoEditor" }

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

        player = MediaPlayerController(this, binding.textureView, lifecycleScope)
        pipeline = ProcessingPipeline(this, lifecycleScope, externalCacheDir ?: cacheDir)

        setupKeyboardInsets(binding.root, binding.nestedScrollView)
        setupListeners()

        // Collect position flow to update seek bar, time display, and play/pause button.
        lifecycleScope.launch {
            player.positionMs.collectLatest { pos ->
                val dur = videoDurationMs.coerceAtLeast(1L)
                binding.trimRangeView.setCurrentTime(pos)
                binding.seekBar.progress = (pos * 1000L / dur).toInt().coerceIn(0, 1000)
                binding.tvPlaybackTime.text =
                    "${TimeFormat.formatTime(pos)} / ${TimeFormat.formatTime(videoDurationMs)}"
            }
        }
        lifecycleScope.launch {
            player.isPlaying.collectLatest { playing ->
                binding.btnPlayPause.text = if (playing) "⏸" else "▶"
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_select_video) { requestVideoPermission(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataJob?.cancel()
        progressPollingJob?.cancel()
        player.release()
        pipeline.cancelAndCleanup()
        VideoProcessingService.stopProcessing(this)
    }

    // ── UI wiring ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.noVideoPlaceholder.setOnClickListener { requestVideoPermission() }
        binding.btnCut.setOnClickListener { processVideo() }

        // Toggles
        binding.btnToggleTrim.addOnCheckedChangeListener { _, checked ->
            binding.trimSection.isVisible = checked
        }
        binding.btnToggleRotation.addOnCheckedChangeListener { _, checked ->
            binding.rotationSection.isVisible = checked
        }
        binding.btnToggleCrop.addOnCheckedChangeListener { _, checked ->
            binding.cropSection.isVisible = checked
            binding.cropOverlayView.setOverlayEnabled(checked)
            if (checked) refreshCropInfo()
        }

        // Rotation controls
        binding.btnRotateMinus90.setOnClickListener { adjustRotation(-90) }
        binding.btnRotatePlus90.setOnClickListener  { adjustRotation(+90) }
        binding.etRotation.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (suppressRotationWatcher) return
                rotationDeltaDeg = s?.toString()?.toIntOrNull() ?: return
                refreshEffectiveRotationHint()
            }
        })

        // Crop overlay callback
        binding.cropOverlayView.onCropChanged = { _, _, _, _ -> refreshCropInfo() }

        // TrimRangeView ↔ text fields
        binding.trimRangeView.onStartTimeChanged = { ms ->
            setTextSilently(binding.etStartTime, TimeFormat.formatMsec(ms))
            refreshLabels()
        }
        binding.trimRangeView.onEndTimeChanged = { ms ->
            setTextSilently(binding.etEndTime, TimeFormat.formatMsec(ms))
            refreshLabels()
        }
        binding.trimRangeView.onSeek = { ms -> player.seekTo(ms) }

        // Playback controls
        binding.btnPlayPause.setOnClickListener { player.togglePlayPause() }
        binding.btnSkipBack.setOnClickListener    { player.skipBy(-10_000L, videoDurationMs) }
        binding.btnSkipForward.setOnClickListener { player.skipBy(+10_000L, videoDurationMs) }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) player.seekTo(progress.toLong() * videoDurationMs / 1000L)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Text fields → TrimRangeView
        binding.etStartTime.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                val ms = TimeFormat.parseTime(s?.toString() ?: return, videoDurationMs) ?: return
                binding.trimRangeView.setStartTime(ms)
                refreshLabels()
            }
        })
        binding.etEndTime.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextWatcher) return
                val ms = TimeFormat.parseTime(s?.toString() ?: return, videoDurationMs) ?: return
                binding.trimRangeView.setEndTime(ms)
                refreshLabels()
            }
        })
    }

    // ── Permission + picker ───────────────────────────────────────────────────

    private fun requestVideoPermission() {
        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            launchVideoPicker()
        else
            permissionLauncher.launch(perm)
    }

    private fun launchVideoPicker() = videoPickerLauncher.launch("video/*")

    // ── Video loading ─────────────────────────────────────────────────────────

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        videoDurationMs = 0L
        videoDisplayW = 0; videoDisplayH = 0

        binding.noVideoPlaceholder.isVisible = false
        binding.btnToggleTrim.isChecked = false
        binding.btnToggleRotation.isChecked = false
        binding.btnToggleCrop.isChecked = false

        rotationDeltaDeg = 0; originalRotationDeg = 0
        setRotationTextSilently(0); refreshEffectiveRotationHint()

        metadataJob?.cancel()
        binding.playerControls.isVisible = false

        player.onPrepared = { w, h ->
            if (w > 0 && h > 0) { videoDisplayW = w; videoDisplayH = h }
            player.updateTransform(videoDisplayW, videoDisplayH)
            binding.playerControls.isVisible = true
            binding.btnPlayPause.text = "▶"
        }
        player.onVideoSizeChanged = { w, h ->
            videoDisplayW = w; videoDisplayH = h
            binding.cropOverlayView.setVideoInfo(w, h)
            player.updateTransform(w, h)
            Log.d(TAG, "videoSize=${w}x${h}")
        }
        player.onError = { what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
        }

        try {
            player.load(uri)
        } catch (e: Exception) {
            Log.e(TAG, "loadVideo failed", e)
            Toast.makeText(this, "Could not open video: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Metadata + thumbnails on IO thread (independent of player preparation)
        metadataJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val meta = VideoMetadataLoader.loadMetadata(this@MainActivity, uri)
                if (meta == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not read video metadata — unsupported format?",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    videoDurationMs     = meta.durationMs
                    originalRotationDeg = meta.rotationDeg
                    if (meta.displayWidth > 0 && meta.displayHeight > 0) {
                        videoDisplayW = meta.displayWidth
                        videoDisplayH = meta.displayHeight
                        binding.cropOverlayView.setVideoInfo(meta.displayWidth, meta.displayHeight)
                    }
                    onVideoMetadataReady()
                }

                val thumbs = VideoMetadataLoader.generateThumbnails(
                    this@MainActivity, uri, meta.durationMs,
                )
                withContext(Dispatchers.Main) { binding.trimRangeView.setThumbnails(thumbs) }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not open video: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun onVideoMetadataReady() {
        binding.trimRangeView.setDuration(videoDurationMs)
        binding.trimRangeView.setStartTime(0L)
        binding.trimRangeView.setEndTime(videoDurationMs)
        setTextSilently(binding.etStartTime, "0:00.000")
        setTextSilently(binding.etEndTime, TimeFormat.formatMsec(videoDurationMs))
        refreshLabels()
        binding.btnCut.isEnabled = true
        binding.btnToggleTrim.isEnabled = true
        binding.btnToggleRotation.isEnabled = true
        binding.btnToggleCrop.isEnabled = true
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private fun processVideo() {
        val uri = videoUri ?: run {
            Toast.makeText(this, getString(R.string.error_no_video), Toast.LENGTH_SHORT).show()
            return
        }

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

        val effectiveRot  = (originalRotationDeg + rotDelta).mod(360)
        val isLosslessRot = effectiveRot % 90 == 0

        // Release the player synchronously so the hardware codec is free for encoding.
        val resumePositionMs = player.currentPositionMs()
        player.release()
        binding.playerControls.isVisible = false

        if (cropPx == null && isLosslessRot) {
            // ── Lossless path ──────────────────────────────────────────────────
            showProgress(getString(R.string.cutting_video))
            lifecycleScope.launch {
                val result = pipeline.runLossless(
                    inputUri    = uri,
                    startMs     = startMs,
                    endMs       = endMs,
                    durationMs  = videoDurationMs,
                    rotDelta    = rotDelta,
                    onProgress  = { pct -> runOnUiThread { updateProgress(pct) } },
                )
                hideProgress()
                result.fold(
                    onSuccess = { showSaveResult(it) },
                    onFailure = { e ->
                        Toast.makeText(this@MainActivity, buildErrorMessage(e), Toast.LENGTH_LONG).show()
                    },
                )
                restorePlayer(uri, resumePositionMs)
            }
        } else {
            // ── Re-encode path ─────────────────────────────────────────────────
            showProgress(getString(R.string.processing_video))
            lifecycleScope.launch {
                try {
                    pipeline.startReEncode(
                        inputUri     = uri,
                        startMs      = startMs,
                        endMs        = endMs,
                        durationMs   = videoDurationMs,
                        cropPixels   = cropPx,
                        videoDisplayW = videoDisplayW,
                        videoDisplayH = videoDisplayH,
                        rotDelta     = rotDelta,
                        onSuccess = { savedUri ->
                            hideProgress()
                            showSaveResult(savedUri)
                            restorePlayer(uri, resumePositionMs)
                        },
                        onFailure = { e ->
                            hideProgress()
                            Toast.makeText(this@MainActivity, buildErrorMessage(e), Toast.LENGTH_LONG).show()
                            restorePlayer(uri, resumePositionMs)
                        },
                    )
                    startProgressPolling()
                } catch (e: Exception) {
                    hideProgress()
                    Toast.makeText(this@MainActivity, buildErrorMessage(e), Toast.LENGTH_LONG).show()
                    restorePlayer(uri, resumePositionMs)
                }
            }
        }
    }

    private fun restorePlayer(uri: Uri, resumePositionMs: Long) {
        player.onPrepared = { w, h ->
            if (w > 0 && h > 0) { videoDisplayW = w; videoDisplayH = h }
            player.updateTransform(videoDisplayW, videoDisplayH)
            player.seekTo(resumePositionMs)
            binding.playerControls.isVisible = true
            binding.btnPlayPause.text = "▶"
        }
        player.onVideoSizeChanged = { w, h ->
            videoDisplayW = w; videoDisplayH = h
            binding.cropOverlayView.setVideoInfo(w, h)
            player.updateTransform(w, h)
        }
        player.onError = { what, extra ->
            Log.e(TAG, "restorePlayer error: what=$what extra=$extra")
        }
        try { player.load(uri) } catch (e: Exception) {
            Log.e(TAG, "restorePlayer load failed", e)
        }
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private fun showProgress(label: String) {
        binding.tvProgressLabel.text = label
        binding.progressBar.isIndeterminate = true
        binding.tvProgressPercent.isVisible = false
        binding.progressLayout.isVisible = true
        binding.btnCut.isEnabled = false
        VideoProcessingService.startProcessing(this, label)
    }

    private fun updateProgress(percent: Int) {
        if (binding.progressBar.isIndeterminate) binding.progressBar.isIndeterminate = false
        binding.progressBar.setProgressCompat(percent, true)
        binding.tvProgressPercent.text = "$percent%"
        binding.tvProgressPercent.isVisible = true
        VideoProcessingService.updateProgress(this, percent, binding.tvProgressLabel.text.toString())
    }

    private fun hideProgress() {
        progressPollingJob?.cancel()
        progressPollingJob = null
        binding.progressLayout.isVisible = false
        binding.btnCut.isEnabled = true
        VideoProcessingService.stopProcessing(this)
    }

    private fun startProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                delay(500)
                val pct = pipeline.pollProgress()
                if (pct >= 0) updateProgress(pct)
                if (pipeline.activeTransformer == null) break
            }
        }
    }

    // ── Rotation helpers ──────────────────────────────────────────────────────

    private fun adjustRotation(delta: Int) {
        rotationDeltaDeg = (rotationDeltaDeg + delta).let {
            when { it >= 360 -> it - 360; it <= -360 -> it + 360; else -> it }
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
        if (!binding.btnToggleCrop.isChecked) { binding.tvCropInfo.isVisible = false; return }
        val rect = binding.cropOverlayView.getCropRectInVideoPixels() ?: return
        binding.tvCropInfo.text =
            getString(R.string.crop_info, rect.right - rect.left, rect.bottom - rect.top, rect.left, rect.top)
        binding.tvCropInfo.isVisible = true
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun refreshLabels() {
        val start = binding.trimRangeView.getStartTime()
        val end   = binding.trimRangeView.getEndTime()
        binding.tvStartDisplay.text    = "Start: ${TimeFormat.formatMsec(start)}"
        binding.tvEndDisplay.text      = "End: ${TimeFormat.formatMsec(end)}"
        binding.tvDurationDisplay.text = "Cut: ${TimeFormat.formatMsec(end - start)}"
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

    private fun showSaveResult(savedUri: Uri?) {
        Toast.makeText(
            this,
            if (savedUri != null) getString(R.string.saved_ok) else "Saved but could not write to gallery",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun buildErrorMessage(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("ENOMEM", ignoreCase = true) ||
            msg.contains("OutOfMemory", ignoreCase = true) ->
                "Not enough memory to process this video. Try trimming to a shorter clip."
            msg.contains("ENOSPC", ignoreCase = true) ||
            msg.contains("No space", ignoreCase = true) ->
                "Not enough storage space for the output file. Free up space and try again."
            msg.contains("4 GB MediaMuxer limit", ignoreCase = false) -> msg
            msg.contains("ETIMEDOUT", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ->
                "Operation timed out — the video may be too large or the device too slow."
            msg.contains("ERROR_IO", ignoreCase = true) ->
                "Could not read the video file. Make sure storage access is granted."
            else -> "Processing failed: $msg"
        }
    }

    abstract class SimpleTextWatcher : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }
}
