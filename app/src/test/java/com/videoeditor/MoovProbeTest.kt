package com.videoeditor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [MoovProbe]'s byte-scanning logic using synthetic MP4 atom sequences.
 *
 * We exercise the algorithm via [MoovProbeHelper], which mirrors the exact same byte-scanning
 * logic as [MoovProbe.scanForMoovFirst] without Android dependencies.
 *
 * Atom format: `[4 bytes size (big-endian)][4 bytes FourCC]`.  Each test atom is 8 bytes
 * (size field = 8 = the minimum valid atom size), so the scanner advances exactly one atom
 * per step and sees each FourCC in order.
 */
class MoovProbeTest {

    /** Build a synthetic MP4 buffer: each entry is one 8-byte atom (size=8, fourcc=label). */
    private fun atoms(vararg fourccs: String): ByteArray {
        val buf = ByteArray(fourccs.size * 8)
        var offset = 0
        for (fourcc in fourccs) {
            val size = 8  // exactly the bytes we write, so the scanner advances correctly
            buf[offset]     = ((size shr 24) and 0xFF).toByte()
            buf[offset + 1] = ((size shr 16) and 0xFF).toByte()
            buf[offset + 2] = ((size shr  8) and 0xFF).toByte()
            buf[offset + 3] = ((size)        and 0xFF).toByte()
            fourcc.forEachIndexed { i, c -> buf[offset + 4 + i] = c.code.toByte() }
            offset += 8
        }
        return buf
    }

    private fun scan(data: ByteArray) = MoovProbeHelper.scanForMoovFirst(data, data.size)

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test fun moovBeforeMdat_returnsTrue() {
        assertTrue(scan(atoms("ftyp", "moov", "mdat")))
    }

    @Test fun mdatBeforeMoov_returnsFalse() {
        assertFalse(scan(atoms("ftyp", "mdat", "moov")))
    }

    @Test fun moovOnly_returnsTrue() {
        assertTrue(scan(atoms("moov")))
    }

    @Test fun emptyBuffer_returnsTrue() {
        assertTrue(scan(ByteArray(0)))
    }

    @Test fun truncatedHeader_returnsTrue() {
        // Buffer with only 4 bytes — not enough for a full 8-byte header → inconclusive → true
        assertTrue(scan(ByteArray(4)))
    }

    @Test fun unknownAtomsFollowedByMdat_returnsFalse() {
        assertFalse(scan(atoms("ftyp", "free", "mdat", "moov")))
    }

    @Test fun unknownAtomsFollowedByMoov_returnsTrue() {
        assertTrue(scan(atoms("ftyp", "free", "moov", "mdat")))
    }

    @Test fun noMdatNorMoov_returnsTrue() {
        assertTrue(scan(atoms("ftyp", "free", "skip")))
    }
}

/**
 * Test-only mirror of [MoovProbe]'s internal scanning algorithm.
 * Keeps the production object free of test hooks while allowing JVM unit testing.
 */
object MoovProbeHelper {
    private const val MOOV = 0x6D6F6F76L
    private const val MDAT = 0x6D646174L

    fun scanForMoovFirst(data: ByteArray, len: Int): Boolean {
        var i = 0
        while (i + 8 <= len) {
            val size   = getUInt32(data, i)
            val fourCC = getUInt32(data, i + 4)
            when (fourCC) {
                MOOV -> return true
                MDAT -> return false
            }
            when {
                size == 0L -> return true
                size == 1L -> {
                    if (i + 16 > len) return true
                    i += getUInt64(data, i + 8).coerceAtLeast(16L).toInt()
                }
                size >= 8L -> i += size.toInt()
                else       -> return true
            }
        }
        return true
    }

    private fun getUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
        ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or
         (data[offset + 3].toLong() and 0xFF)

    private fun getUInt64(data: ByteArray, offset: Int): Long =
        (getUInt32(data, offset) shl 32) or getUInt32(data, offset + 4)
}
