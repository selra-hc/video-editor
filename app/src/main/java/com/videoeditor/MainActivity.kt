package com.videoeditor

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import androidx.media3.exoplayer.ExoPlayer
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
        player?.release()
        player = null
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

        // Reset all tool toggles (this auto-fires listeners, hiding sections + disabling crop)
        binding.btnToggleTrim.isChecked = false
        binding.btnToggleRotation.isChecked = false
        binding.btnToggleCrop.isChecked = false

        // Reset rotation state
        rotationDeltaDeg = 0
        originalRotationDeg = 0
        setRotationTextSilently(0)
        refreshEffectiveRotationHint()

        positionJob?.cancel()
        player?.release()

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = false

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && videoDurationMs == 0L) {
                    val dur = exo.duration
                    if (dur > 0L) {
                        videoDurationMs = dur
                        onVideoReady(uri)
                    }
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoDisplayW = videoSize.width
                    videoDisplayH = videoSize.height
                    binding.cropOverlayView.setVideoInfo(videoDisplayW, videoDisplayH)
                }
            }
        })

        startPositionUpdater()
    }

    private fun onVideoReady(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            originalRotationDeg = readRotationFromMetadata(uri)
            val (w, h) = readEncodedDimensions(uri)
            val dispW = if (originalRotationDeg == 90 || originalRotationDeg == 270) h else w
            val dispH = if (originalRotationDeg == 90 || originalRotationDeg == 270) w else h
            withContext(Dispatchers.Main) {
                if (dispW > 0 && dispH > 0 && videoDisplayW == 0) {
                    videoDisplayW = dispW
                    videoDisplayH = dispH
                    binding.cropOverlayView.setVideoInfo(videoDisplayW, videoDisplayH)
                }
            }
        }

        binding.trimRangeView.setDuration(videoDurationMs)
        binding.trimRangeView.setStartTime(0L)
        binding.trimRangeView.setEndTime(videoDurationMs)
        setTextSilently(binding.etStartTime, "0.00")
        setTextSilently(binding.etEndTime, formatSec(videoDurationMs))
        refreshLabels()

        // Enable all interactive controls now that a video is loaded
        binding.btnCut.isEnabled = true
        binding.btnToggleTrim.isEnabled = true
        binding.btnToggleRotation.isEnabled = true
        binding.btnToggleCrop.isEnabled = true

        generateThumbnails(uri)
    }

    // ── Thumbnail generation ──────────────────────────────────────────────────

    private fun generateThumbnails(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@MainActivity, uri)
                val count = 12
                val bitmaps = (0 until count).mapNotNull { i ->
                    val timeUs = videoDurationMs * 1_000L * i / count
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                withContext(Dispatchers.Main) { binding.trimRangeView.setThumbnails(bitmaps) }
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }
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
            val cacheOut = File(cacheDir, "cut_${System.currentTimeMillis()}.mp4")
            try {
                VideoTrimmer.trim(
                    context       = this@MainActivity,
                    inputUri      = uri,
                    outputFile    = cacheOut,
                    startMs       = startMs,
                    endMs         = endMs,
                    rotationDelta = rotDelta,
                )
                val savedUri = saveToMediaStore(cacheOut)
                withContext(Dispatchers.Main) { hideProgress(); showSaveResult(savedUri) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        val cacheOut = File(cacheDir, "proc_${System.currentTimeMillis()}.mp4")
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
                        showSaveResult(savedUri)
                    }
                }
            },
            onFailure = { e ->
                cacheOut.delete()
                activeTransformer = null
                hideProgress()
                Toast.makeText(this, "Processing failed: ${e.message}", Toast.LENGTH_LONG).show()
            },
        )
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

    // ── Metadata helpers ──────────────────────────────────────────────────────

    private fun readRotationFromMetadata(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { retriever.release() }
    }

    private fun readEncodedDimensions(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            w to h
        } catch (_: Exception) { 0 to 0 } finally { retriever.release() }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showProgress(label: String) {
        binding.tvProgressLabel.text = label
        binding.progressLayout.isVisible = true
        binding.btnCut.isEnabled = false
    }

    private fun hideProgress() {
        binding.progressLayout.isVisible = false
        binding.btnCut.isEnabled = true
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

    abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }
}
