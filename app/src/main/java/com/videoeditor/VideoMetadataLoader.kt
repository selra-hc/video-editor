package com.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/** Immutable metadata snapshot for a video file. */
data class VideoMetadata(
    val durationMs: Long,
    val rotationDeg: Int,
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * Loads video metadata and generates timeline thumbnails from a [Uri] using
 * [MediaMetadataRetriever].
 *
 * [MediaMetadataRetriever] reads directly from the native media stack (same as
 * [android.media.MediaPlayer]) and correctly handles Samsung moov-at-end recordings without any
 * extra seeking logic.  It is the authoritative source for duration, rotation, and frame data.
 *
 * All functions are `suspend` and must be called from an IO coroutine.
 */
object VideoMetadataLoader {

    private const val TAG        = "VideoMetadataLoader"
    private const val THUMB_W    = 160
    private const val THUMB_H    = 90
    private const val THUMB_COUNT = 12

    /**
     * Returns [VideoMetadata] for [uri], or null if the container could not be read or reported a
     * zero/negative duration.
     *
     * @throws Exception propagated from [MediaMetadataRetriever] for truly unreadable URIs
     */
    suspend fun loadMetadata(context: Context, uri: Uri): VideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                Log.w(TAG, "duration=0 for $uri — unsupported format?")
                return null
            }

            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val encW = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val encH = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0

            val displayW = if (rotation == 90 || rotation == 270) encH else encW
            val displayH = if (rotation == 90 || rotation == 270) encW else encH

            VideoMetadata(
                durationMs   = durationMs,
                rotationDeg  = rotation,
                displayWidth = displayW,
                displayHeight = displayH,
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Generates [THUMB_COUNT] evenly-spaced thumbnails from [uri], scaled to [THUMB_W]×[THUMB_H].
     * Returns an empty list on any error (thumbnails are cosmetic; failures must not block the UI).
     *
     * Reuses the same [MediaMetadataRetriever] instance as [loadMetadata] when possible by
     * accepting [durationMs] to avoid re-opening the file.
     */
    fun generateThumbnails(context: Context, uri: Uri, durationMs: Long): List<Bitmap> {
        if (durationMs <= 0L) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            (0 until THUMB_COUNT).mapNotNull { i ->
                val timeUs = durationMs * 1_000L * i / THUMB_COUNT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        THUMB_W,
                        THUMB_H,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val bmp = retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    ) ?: return@mapNotNull null
                    val scaled = Bitmap.createScaledBitmap(bmp, THUMB_W, THUMB_H, true)
                    if (scaled !== bmp) bmp.recycle()
                    scaled
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail generation failed: ${e.message}")
            emptyList()
        } finally {
            retriever.release()
        }
    }
}
