package com.videoeditor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
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
     * @throws Exception on any I/O or media error
     */
    fun trim(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        rotationDelta: Int = 0,
    ) {
        val startUs = startMs * 1_000L
        val endUs = endMs * 1_000L

        // ── Compute effective orientation hint ────────────────────────────────
        val originalRotation = readRotation(context, inputUri)
        val rotation = snapToValidHint((originalRotation + rotationDelta).mod(360))

        val extractor = MediaExtractor()
        extractor.setDataSource(context, inputUri, null)

        if (outputFile.exists()) outputFile.delete()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)

        // ── Map extractor track index → muxer track index ─────────────────────
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            extractor.selectTrack(i)
            trackMap[i] = muxer.addTrack(format)
        }

        muxer.start()

        // ── Seek to the closest sync frame at or before startUs ───────────────
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        // The actual seek position (may be slightly before startUs)
        val seekOffsetUs: Long = extractor.sampleTime.coerceAtLeast(0L)

        // ── Copy samples ──────────────────────────────────────────────────────
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024) // 2 MB
        val info = MediaCodec.BufferInfo()

        while (true) {
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break

            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs > endUs) break

            info.offset = 0
            info.presentationTimeUs = sampleTimeUs - seekOffsetUs
            info.flags = extractor.sampleFlags

            val muxerTrack = trackMap[extractor.sampleTrackIndex]
            if (muxerTrack != null) {
                muxer.writeSampleData(muxerTrack, buffer, info)
            }
            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }

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
