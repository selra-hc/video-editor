package com.videoeditor

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec

/**
 * A seekable [DataSource] for content:// URIs backed by real on-device files.
 *
 * ### Why not [androidx.media3.datasource.ContentDataSource]?
 *
 * `ContentDataSource` obtains its file descriptor via
 * [ContentResolver.openTypedAssetFileDescriptor].  On Samsung devices (and others) that
 * method can return a **pipe** fd for large media files — particularly camera recordings
 * larger than ~4 GB.  A pipe cannot be `lseek`'d: the only way to "seek" it is to read
 * and discard every byte up to the target position.
 *
 * `Mp4Extractor` needs to skip the huge `mdat` box and jump directly to the `moov` atom
 * sitting at the **end** of the file (the default Samsung recorder layout).  For a 9–15 GB
 * file that skip is 8–14 GB — reading through a pipe to do it never completes within any
 * reasonable timeout.  As a result `Player.getDuration()` stays `C.TIME_UNSET` and the
 * seek bar stays permanently grey.
 *
 * ### The fix
 *
 * [ContentResolver.openFileDescriptor] with `"r"` **always** returns a regular-file fd
 * (never a pipe).  We then use [Os.lseek] (O(1) on a real file) to jump to the requested
 * byte offset and [Os.read] to read data sequentially from there.
 *
 * A fresh [ParcelFileDescriptor] is opened for each [open] call so that multiple
 * [DataSource] instances created by the factory lambda do not share fd-position state.
 * The Binder round-trip overhead of re-opening is negligible — seeks happen only a handful
 * of times per container parse.
 *
 * @param resolver   The [ContentResolver] to use.
 * @param contentUri The content:// URI of the media file.
 * @param knownLength The real file size in bytes (from `Os.fstat()`), or -1 if unknown.
 *                    Supplying the correct size lets `Mp4Extractor` calculate the moov
 *                    position without needing to scan the entire file.
 */
class SeekableContentDataSource(
    private val resolver: ContentResolver,
    private val contentUri: Uri,
    private val knownLength: Long,
) : BaseDataSource(/* isNetwork= */ false) {

    private var pfd: ParcelFileDescriptor? = null
    private var position: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        closePfd()

        // openFileDescriptor always gives a real file fd, never a pipe.
        val newPfd = try {
            resolver.openFileDescriptor(contentUri, "r")
        } catch (_: Exception) {
            null
        } ?: return C.LENGTH_UNSET.toLong()
        pfd = newPfd

        position = dataSpec.position

        // O(1) seek via lseek on the regular-file fd.
        if (position > 0L) {
            try {
                Os.lseek(newPfd.fileDescriptor, position, OsConstants.SEEK_SET)
            } catch (_: Exception) {
                closePfd()
                return C.LENGTH_UNSET.toLong()
            }
        }

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            knownLength > 0L -> (knownLength - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val fd = pfd?.fileDescriptor ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
                     else minOf(length.toLong(), bytesRemaining).toInt()
        val n = try {
            Os.read(fd, buffer, offset, toRead)
        } catch (_: Exception) {
            return C.RESULT_END_OF_INPUT
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri() = contentUri

    override fun close() {
        closePfd()
        transferEnded()
    }

    private fun closePfd() {
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }
}
