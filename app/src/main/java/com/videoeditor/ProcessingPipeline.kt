package com.videoeditor

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Encapsulates the full video-processing pipeline: chooses between the lossless
 * (MediaExtractor + MediaMuxer) path and the re-encoding (Media3 Transformer) path,
 * manages two-pass pre-mux for moov-at-end files, enforces the 4 GB MediaMuxer limit,
 * cleans up temporary files, and reports progress via [VideoProcessingService].
 *
 * ## Processing strategy
 *
 * ```
 * no crop AND rotation % 90 == 0  →  VideoTrimmer  (lossless stream-copy, fastest path)
 * crop OR arbitrary rotation      →  VideoProcessor / Transformer (re-encode)
 *     ├─ MoovProbe: moov at front →  Transformer on original URI directly
 *     └─ moov at end (Samsung…)   →  pass-1: VideoTrimmer pre-mux (moov → front)
 *                                     pass-2: Transformer on temp file (no backward seeks)
 * ```
 *
 * ## Temp-file tracking
 * [pendingTempFiles] is populated before any long operation and cleared on success/failure.
 * Call [cancelAndCleanup] from `Activity.onDestroy` so files are not leaked if the user
 * navigates away mid-processing.
 *
 * Constructor must be called on the **main thread**; [startReEncode] must also be called
 * from the main thread (Transformer requirement).
 *
 * @param context    Application / activity context.
 * @param scope      Lifecycle-bound scope (e.g. `Activity.lifecycleScope`).
 * @param outputDir  Directory for temp and final output files (prefer `externalCacheDir`).
 */
class ProcessingPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val outputDir: File,
) {

    private val TAG = "ProcessingPipeline"

    /** Currently active Transformer, if a re-encode pass is in progress. */
    var activeTransformer: Transformer? = null
        private set

    private val pendingTempFiles = mutableListOf<File>()

    // ── 4 GB guard ────────────────────────────────────────────────────────────

    private fun willExceedMuxerLimit(estimatedBytes: Long): Boolean =
        estimatedBytes > 4L * 1_024 * 1_024 * 1_024 &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun estimateClipBytes(uri: Uri, startMs: Long, endMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val fileSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }
        return (fileSize * (endMs - startMs).toDouble() / durationMs.toDouble()).toLong()
    }

    // ── Lossless path ─────────────────────────────────────────────────────────

    /**
     * Runs the lossless trim+rotate path on the IO dispatcher.
     * Suspends until the operation completes (or fails).
     * Returns a [Result] wrapping the MediaStore [Uri] of the saved file, or a failure.
     *
     * [onProgress] is invoked with 0–100 from the IO thread — dispatch to main if UI needed.
     */
    suspend fun runLossless(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        rotDelta: Int,
        onProgress: (Int) -> Unit,
    ): Result<Uri?> = withContext(Dispatchers.IO) {
        val estimatedBytes = estimateClipBytes(inputUri, startMs, endMs, durationMs)
        if (willExceedMuxerLimit(estimatedBytes)) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Output exceeds 4 GB MediaMuxer limit on Android 9 and older. " +
                        "Trim to a shorter clip or upgrade to Android 10+.",
                ),
            )
        }

        val cacheOut = File(outputDir, "cut_${System.currentTimeMillis()}.mp4")
        pendingTempFiles += cacheOut
        try {
            VideoTrimmer.trim(
                context       = context,
                inputUri      = inputUri,
                outputFile    = cacheOut,
                startMs       = startMs,
                endMs         = endMs,
                rotationDelta = rotDelta,
                onProgress    = onProgress,
            )
            MediaStoreSaver.save(context, cacheOut).fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(it) },
            )
        } finally {
            cacheOut.delete()
            pendingTempFiles.remove(cacheOut)
        }
    }

    // ── Re-encode path ────────────────────────────────────────────────────────

    /**
     * Starts the re-encode (Transformer) path.  **Must be called from the main thread.**
     *
     * The function suspends briefly for IO-bound work (moov probe, optional pre-mux), then
     * kicks off Transformer asynchronously and returns immediately.  Progress is delivered via
     * [pollProgress]; completion/failure via [onSuccess]/[onFailure].
     *
     * [onSuccess] and [onFailure] are called on the **main thread** by Transformer's listener.
     */
    suspend fun startReEncode(
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        cropPixels: Rect?,
        videoDisplayW: Int,
        videoDisplayH: Int,
        rotDelta: Int,
        onSuccess: (Uri?) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        val cacheOut = File(outputDir, "proc_${System.currentTimeMillis()}.mp4")
        pendingTempFiles += cacheOut

        // ── Probe moov position (IO) ───────────────────────────────────────────
        val moovAtFront = withContext(Dispatchers.IO) { MoovProbe.isMoovAtFront(context, inputUri) }

        val transformUri: Uri
        val transformStart: Long
        val transformEnd: Long
        var preMuxed: File? = null

        if (moovAtFront) {
            Log.d(TAG, "moov is at front — skipping pre-mux pass")
            transformUri   = inputUri
            transformStart = startMs
            transformEnd   = endMs
        } else {
            // ── Pass 1: pre-mux (IO) ──────────────────────────────────────────
            Log.d(TAG, "moov is at end — starting pre-mux pass")

            val estimatedBytes = withContext(Dispatchers.IO) {
                estimateClipBytes(inputUri, startMs, endMs, durationMs)
            }
            if (willExceedMuxerLimit(estimatedBytes)) {
                cacheOut.delete()
                pendingTempFiles.remove(cacheOut)
                throw IllegalStateException(
                    "Output exceeds 4 GB MediaMuxer limit on Android 9 and older. " +
                        "Trim to a shorter clip or upgrade to Android 10+.",
                )
            }

            val temp = File(outputDir, "premux_${System.currentTimeMillis()}.mp4")
            pendingTempFiles += temp
            var pass1Ok = false

            withContext(Dispatchers.IO) {
                try {
                    VideoTrimmer.trim(
                        context       = context,
                        inputUri      = inputUri,
                        outputFile    = temp,
                        startMs       = startMs,
                        endMs         = endMs,
                        rotationDelta = 0,  // rotation handled in pass 2
                        onProgress    = null,
                    )
                    pass1Ok = true
                    Log.d(TAG, "pre-mux OK: ${temp.length() / 1_048_576} MB")
                } catch (e: Exception) {
                    Log.w(TAG, "pre-mux failed (${e.message}); falling back to direct URI")
                    temp.delete()
                    pendingTempFiles.remove(temp)
                }
            }

            if (pass1Ok) {
                preMuxed       = temp
                transformUri   = Uri.fromFile(temp)
                transformStart = 0L
                transformEnd   = (endMs - startMs).coerceAtLeast(1L)
            } else {
                transformUri   = inputUri
                transformStart = startMs
                transformEnd   = endMs
            }
        }

        // ── Pass 2: Transformer (must be on main thread) ──────────────────────
        val capturedPreMuxed = preMuxed
        val transformer = VideoProcessor.process(
            context          = context,
            inputUri         = transformUri,
            outputFile       = cacheOut,
            startMs          = transformStart,
            endMs            = transformEnd,
            cropPixels       = cropPixels,
            videoDisplayW    = videoDisplayW,
            videoDisplayH    = videoDisplayH,
            rotationDeltaDeg = rotDelta,
            onSuccess = {
                scope.launch(Dispatchers.IO) {
                    capturedPreMuxed?.delete()
                    pendingTempFiles.remove(capturedPreMuxed)
                    val saved = MediaStoreSaver.save(context, cacheOut)
                    cacheOut.delete()
                    pendingTempFiles.remove(cacheOut)
                    withContext(Dispatchers.Main) {
                        activeTransformer = null
                        onSuccess(saved.getOrNull())
                    }
                }
            },
            onFailure = { e ->
                capturedPreMuxed?.delete()
                pendingTempFiles.remove(capturedPreMuxed)
                cacheOut.delete()
                pendingTempFiles.remove(cacheOut)
                activeTransformer = null
                onFailure(e)
            },
        )
        activeTransformer = transformer
    }

    // ── Progress polling ──────────────────────────────────────────────────────

    /** Returns the current Transformer export progress (0–100), or -1 if unavailable. */
    fun pollProgress(): Int {
        val transformer = activeTransformer ?: return -1
        val holder = ProgressHolder()
        return if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE)
            holder.progress
        else -1
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /** Cancels any in-flight Transformer and deletes all tracked temp files. */
    fun cancelAndCleanup() {
        try { activeTransformer?.cancel() } catch (_: Exception) {}
        activeTransformer = null
        pendingTempFiles.forEach { it.delete() }
        pendingTempFiles.clear()
    }
}
