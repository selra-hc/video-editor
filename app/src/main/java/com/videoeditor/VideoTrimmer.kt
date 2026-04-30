package com.videoeditor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Lossless video trimmer using Android's built-in MediaExtractor + MediaMuxer.
 * No re-encoding takes place: each compressed frame is copied byte-for-byte from
 * input to output.  The output timestamps are shifted so the clip starts at 0.
 *
 * Limitations (inherited from stream-copy in general):
 *  - The actual start point is aligned to the nearest sync (key) frame before the
 *    requested start time, just like `ffmpeg -c copy`.
 *  - Container: MP4 output only.
 *  - Files larger than 4 GB require Android 10 (API 29) or newer; on older devices the
 *    output is capped at ~4 GB and the remainder is silently dropped by MediaMuxer.
 */
object VideoTrimmer {

    /**
     * @param context        Android context (needed to open the content URI)
     * @param inputUri       Source video URI (may be a content:// URI from the picker)
     * @param outputFile     Destination file path to write the trimmed MP4
     * @param startMs        Trim start in milliseconds
     * @param endMs          Trim end   in milliseconds
     * @param rotationDelta  Extra rotation degrees to add to the video's existing orientation hint.
     *                       Only multiples of 90 are valid for the MP4 orientation hint; the value
     *                       is normalised to {0, 90, 180, 270} by rounding to the nearest multiple.
     * @param onProgress     Optional callback invoked with 0–100 as the mux progresses.
     *                       Called on the caller's thread — dispatch to the main thread if needed.
     * @throws Exception on any I/O or media error
     */
    fun trim(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        rotationDelta: Int = 0,
        onProgress: ((Int) -> Unit)? = null,
    ) {
        val startUs = startMs * 1_000L
        val endUs   = endMs   * 1_000L

        // ── Compute effective orientation hint ────────────────────────────────
        val originalRotation = readRotation(context, inputUri)
        val rotation = snapToValidHint((originalRotation + rotationDelta).mod(360))

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, inputUri, null)

            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)

            val trackCount = extractor.trackCount

            // ── Read track formats BEFORE selecting tracks ────────────────────
            // KEY_MAX_INPUT_SIZE tells us the maximum compressed sample size for
            // each track, which we need to allocate a correctly-sized read buffer.
            // UHD HEVC I-frames can easily exceed the old 2 MB hard-coded value.
            val trackFormats = Array(trackCount) { extractor.getTrackFormat(it) }

            // Buffer floor: 8 MB covers UHD HEVC I-frames at typical Samsung bitrates.
            // We then take the max reported by the track format (which may be higher for
            // very high-bitrate recordings).
            var bufferSize = 8 * 1024 * 1024
            for (format in trackFormats) {
                val reported = format.getIntegerSafe(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (reported > bufferSize) bufferSize = reported
            }

            // ── Map extractor tracks → muxer tracks ───────────────────────────
            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until trackCount) {
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(trackFormats[i])
            }

            muxer.start()

            // ── Seek to the closest sync frame at or before startUs ───────────
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // The actual seek position (may be slightly before startUs).
            // Clamped to 0 so we never subtract a negative base.
            val seekOffsetUs: Long = extractor.sampleTime.coerceAtLeast(0L)

            // ── Copy samples ──────────────────────────────────────────────────
            val buffer = ByteBuffer.allocate(bufferSize)
            val info   = MediaCodec.BufferInfo()
            val clipDurationUs = ((endMs - startMs) * 1_000L).coerceAtLeast(1L)
            var lastPct = -1

            while (true) {
                info.size = extractor.readSampleData(buffer, 0)
                // readSampleData returns -1 at true end-of-stream AND when the
                // buffer is too small (API 28+).  With the dynamic allocation above
                // this should only occur at actual EOS.
                if (info.size < 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break

                info.offset = 0
                // Clamp to 0: for B-frame HEVC or when the seek offset is taken
                // from a different track, the first few frames can produce a slightly
                // negative PTS after subtraction.  MediaMuxer rejects negative PTSes.
                info.presentationTimeUs = (sampleTimeUs - seekOffsetUs).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags

                val muxerTrack = trackMap[extractor.sampleTrackIndex]
                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, buffer, info)
                }

                // Report progress throttled to 1 % increments to avoid flooding the UI.
                if (onProgress != null) {
                    val pct = (info.presentationTimeUs * 100L / clipDurationUs)
                        .toInt().coerceIn(0, 99)
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }

                extractor.advance()
            }

            onProgress?.invoke(100)
            muxer.stop()

        } finally {
            // Guaranteed cleanup even if an exception was thrown mid-mux
            muxer?.release()
            extractor.release()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Snap any rotation value to the nearest orientation hint accepted by MediaMuxer {0, 90, 180, 270}.
     * Uses circular distance so that e.g. 359° rounds to 0° rather than 270°.
     */
    fun snapToValidHint(degrees: Int): Int {
        val d = degrees.mod(360)
        val valid = intArrayOf(0, 90, 180, 270)
        return valid.minByOrNull { v ->
            val diff = Math.abs(v - d)
            minOf(diff, 360 - diff)
        } ?: 0
    }

    /**
     * Returns [MediaFormat.getInteger] for [key], or 0 if the key is absent or
     * the format raises an exception (which happens on some vendor codecs).
     */
    private fun MediaFormat.getIntegerSafe(key: String): Int =
        runCatching { getInteger(key) }.getOrDefault(0)

    private fun readRotation(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }
}
