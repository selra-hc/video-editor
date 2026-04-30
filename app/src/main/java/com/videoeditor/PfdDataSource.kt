package com.videoeditor

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec

/**
 * A [androidx.media3.datasource.DataSource] backed by a [ParcelFileDescriptor] that performs
 * all reads via [Os.pread] — a positional-read syscall that does **not** move the file
 * descriptor's seek pointer.
 *
 * Why this matters for large HEVC files:
 *
 *  - ExoPlayer's [androidx.media3.datasource.ContentDataSource] opens the video as an
 *    [android.content.res.AssetFileDescriptor] and wraps it in a [java.io.FileInputStream].
 *    When [androidx.media3.extractor.mp4.Mp4Extractor] needs to jump backwards to read the
 *    moov atom at the **end** of a large file (e.g. 9 GB Samsung recording), ExoPlayer
 *    calls `close()` + `open(DataSpec(uri, endOffset, ...))`.  ContentDataSource handles the
 *    new position by calling `FileInputStream.skip(endOffset)` — which reads and discards
 *    ~9 GB of data and effectively never completes.  The moov is never parsed, duration
 *    stays `C.TIME_UNSET`, and the seek bar is permanently greyed out.
 *
 *  - This class uses [Os.pread] instead.  Every call to [read] reads from [readPos] without
 *    touching the underlying fd position, so ExoPlayer "seeks" become O(1) operations
 *    (just updating [readPos]).  The file size comes from [Os.fstat], which always returns
 *    the correct 64-bit size regardless of whether the content URI reports it.
 *
 * The [ParcelFileDescriptor] is **not** closed by this class; the caller owns its lifecycle.
 */
class PfdDataSource(pfd: ParcelFileDescriptor) : BaseDataSource(/* isNetwork= */ false) {

    private val fd       = pfd.fileDescriptor
    private val fileSize = try { Os.fstat(fd).st_size } catch (_: Exception) { -1L }

    private var readPos   = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        readPos = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileSize >= 0 -> (fileSize - readPos).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length
                     else minOf(length.toLong(), remaining).toInt()
        val n = try {
            Os.pread(fd, buffer, offset, toRead, readPos)
        } catch (_: Exception) {
            return C.RESULT_END_OF_INPUT
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        readPos += n
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = null
    override fun close() { /* caller owns the pfd */ }
}
