package com.videoeditor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel

/**
 * Copies a processed output file into the device's public Movies library via [MediaStore].
 *
 * Uses [FileChannel.transferTo] instead of `InputStream.copyTo` to avoid allocating a JVM heap
 * bounce buffer for multi-gigabyte files; on Linux the kernel performs the copy directly between
 * file descriptors (zero-copy when both are on the same filesystem).
 *
 * On Android 10+ the row is inserted with `IS_PENDING = 1` so media scanners ignore it while the
 * copy is in progress; it is cleared to `0` once the write completes.  On failure the orphaned
 * `IS_PENDING = 1` row is deleted to prevent MediaStore from showing a broken entry.
 *
 * Must be called from an IO coroutine.
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"

    fun save(context: Context, file: File): Result<Uri> {
        val resolver = context.contentResolver
        var insertedUri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/VideoEditor",
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            insertedUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(IllegalStateException("MediaStore.insert returned null"))

            // Open both ends as FileChannels for kernel-level transfer on supported kernels.
            val destPfd = resolver.openFileDescriptor(insertedUri, "w")
            if (destPfd != null) {
                destPfd.use { pfd ->
                    FileChannel.open(file.toPath()).use { src ->
                        FileOutputStream(pfd.fileDescriptor).channel.use { dst ->
                            var pos = 0L
                            val size = src.size()
                            while (pos < size) {
                                pos += src.transferTo(pos, size - pos, dst)
                            }
                        }
                    }
                }
            } else {
                // Fallback for rare cases where the descriptor cannot be obtained.
                resolver.openOutputStream(insertedUri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pending = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(insertedUri, pending, null, null)
            }

            Log.d(TAG, "saved ${file.name} → $insertedUri")
            Result.success(insertedUri)
        } catch (e: Exception) {
            Log.e(TAG, "save failed for ${file.name}", e)
            // Delete the orphaned IS_PENDING row so MediaStore stays clean.
            insertedUri?.let {
                try { resolver.delete(it, null, null) } catch (_: Exception) {}
            }
            Result.failure(e)
        }
    }
}
