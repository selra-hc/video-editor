package com.videoeditor

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Probes the first [PROBE_BYTES] of an MP4/MOV file to determine whether the `moov` atom
 * (Movie Box) appears **before** the `mdat` (Media Data) atom.
 *
 * Why this matters:
 * When `moov` is at the end of the file (common in Samsung camera recordings), Media3 Transformer's
 * internal ExoPlayer receives a pipe file descriptor from ContentDataSource and cannot seek
 * backward to read `moov` after skipping the giant `mdat`.  This triggers "Asset loader error".
 * The two-pass workaround in [ProcessingPipeline] pre-muxes the file so that `moov` is moved
 * to the front.
 *
 * If `moov` is already at the front (standard web-optimised MP4s, most non-Samsung recordings),
 * the pre-mux pass is unnecessary and wastes a full read+write of the source file.
 * [isMoovAtFront] lets [ProcessingPipeline] skip that pass when it's safe to do so.
 *
 * Implementation note: an MP4 atom is `[4 bytes size][4 bytes FourCC]`.  We scan the first
 * [PROBE_BYTES] looking for the FourCC `moov` or `mdat`.  The first one found determines order.
 * We do not attempt to fully parse the container; we only need the relative positions.
 */
object MoovProbe {

    private const val TAG         = "MoovProbe"
    private const val PROBE_BYTES = 65_536        // 64 KB — enough for most containers
    private const val MOOV        = 0x6D6F6F76L   // 'moov'
    private const val MDAT        = 0x6D646174L   // 'mdat'

    /**
     * Returns `true` if the `moov` atom appears before `mdat` in [uri], or if the probe is
     * inconclusive (safe default: skip the pre-mux only when we are certain it is not needed).
     * Returns `false` (triggering pre-mux) if `mdat` appears first.
     *
     * Must be called on an IO thread.
     */
    fun isMoovAtFront(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(PROBE_BYTES)
                val read = stream.readNBytes(buf, 0, PROBE_BYTES)
                scanForMoovFirst(buf, read)
            } ?: true  // can't open → assume front (safer than crashing)
        } catch (e: Exception) {
            Log.w(TAG, "probe failed for $uri: ${e.message}")
            true  // inconclusive → assume front, let Transformer try directly
        }
    }

    /** Scans [data] (first [len] bytes) for the first `moov` or `mdat` atom. */
    private fun scanForMoovFirst(data: ByteArray, len: Int): Boolean {
        var i = 0
        while (i + 8 <= len) {
            val size   = data.getUInt32(i)
            val fourCC = data.getUInt32(i + 4)
            when (fourCC) {
                MOOV -> return true
                MDAT -> return false
            }
            // Advance by atom size; 0 = "rest of file", 1 = 64-bit extended size header.
            when {
                size == 0L -> return true  // "rest of file" atom — no mdat found before it
                size == 1L -> {
                    if (i + 16 > len) return true  // extended-size header would exceed probe buffer
                    i += data.getUInt64(i + 8).coerceAtLeast(16L).toInt()
                }
                size >= 8L -> i += size.toInt()
                else       -> return true  // malformed; treat as inconclusive
            }
        }
        return true  // no mdat found within probe window → assume moov-first
    }

    private fun ByteArray.getUInt32(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
         (this[offset + 3].toLong() and 0xFF)

    private fun ByteArray.getUInt64(offset: Int): Long =
        (getUInt32(offset) shl 32) or getUInt32(offset + 4)
}
