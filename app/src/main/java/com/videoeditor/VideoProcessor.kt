package com.videoeditor

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Re-encoding video processor used when either:
 *  (a) a crop region has been selected, or
 *  (b) the rotation is not a multiple of 90° (cannot be stored losslessly as an orientation hint).
 *
 * Audio is preserved unchanged; only the video track is decoded and re-encoded.
 *
 * Must be called from the **main thread** (Media3 Transformer requirement).
 *
 * @param context           Application context.
 * @param inputUri          Source video URI.
 * @param outputFile        Destination MP4 file.
 * @param startMs           Trim start in milliseconds.
 * @param endMs             Trim end in milliseconds.
 * @param cropPixels        Crop rect in video-display pixel coordinates, or null for no crop.
 * @param videoDisplayW     Display width of the video (after applying its rotation hint).
 * @param videoDisplayH     Display height of the video.
 * @param rotationDeltaDeg  Additional rotation to apply (any angle, positive = counter-clockwise
 *                          in Media3's convention).
 * @param onSuccess         Called on the main thread when export completes.
 * @param onError           Called on the main thread when export fails.
 * @return The [Transformer] instance so the caller can cancel it on destruction.
 */
object VideoProcessor {

    fun process(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        cropPixels: Rect?,
        videoDisplayW: Int,
        videoDisplayH: Int,
        rotationDeltaDeg: Int,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): Transformer {
        if (outputFile.exists()) outputFile.delete()

        // ── Build effect list ─────────────────────────────────────────────────

        val videoEffects = mutableListOf<androidx.media3.common.Effect>()

        if (cropPixels != null && videoDisplayW > 0 && videoDisplayH > 0) {
            // Convert pixel rect to NDC [-1, 1] coords expected by Crop.
            // NDC Y increases upward, so top-of-frame = +1 and bottom-of-frame = -1.
            val ndcLeft   =  cropPixels.left.toFloat()   / videoDisplayW * 2f - 1f
            val ndcRight  =  cropPixels.right.toFloat()  / videoDisplayW * 2f - 1f
            val ndcTop    =  1f - cropPixels.top.toFloat()    / videoDisplayH * 2f
            val ndcBottom =  1f - cropPixels.bottom.toFloat() / videoDisplayH * 2f
            videoEffects.add(Crop(ndcLeft, ndcRight, ndcBottom, ndcTop))
        }

        if (rotationDeltaDeg != 0) {
            videoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(rotationDeltaDeg.toFloat())
                    .build()
            )
        }

        // ── Build MediaItem with optional clipping ────────────────────────────

        val clipping = ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(clipping)
            .build()

        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        // ── Configure and start Transformer ───────────────────────────────────

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onSuccess()
                }
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    onFailure(exportException)
                }
            })
            .build()

        transformer.start(editedItem, outputFile.absolutePath)
        return transformer
    }
}
