package tv.cleanyour.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnnexBVideoTest {
    private fun nal(type: Int, firstByte: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1, type.toByte(), firstByte.toByte(), 0x22)

    @Test
    fun splitsAccessUnitsAcrossArbitraryNetworkChunks() {
        val sps = nal(0x67, 0x42)
        val pps = nal(0x68, 0xce)
        val idr = nal(0x65, 0x80)
        val p1 = nal(0x41, 0x80)
        val p2 = nal(0x41, 0x80)
        val bytes = sps + pps + idr + p1 + p2
        val framer = AnnexBVideo()
        val emitted = ArrayList<ByteArray>()
        bytes.forEach { b -> emitted += framer.accept(byteArrayOf(b)) }
        emitted += framer.finish()
        assertEquals(3, emitted.size)
        assertArrayEquals(sps + pps + idr, emitted[0])
        assertArrayEquals(p1, emitted[1])
        assertArrayEquals(p2, emitted[2])
    }

    @Test
    fun preservesMultipleSlicesInsideOnePicture() {
        val first = nal(0x41, 0x80)  // first_mb_in_slice = 0
        val other = nal(0x41, 0x40)  // first_mb_in_slice > 0
        val next = nal(0x41, 0x80)
        val framer = AnnexBVideo()
        val frames = framer.accept(first + other + next).toMutableList()
        frames += framer.finish()
        assertEquals(2, frames.size)
        assertArrayEquals(first + other, frames[0])
        assertArrayEquals(next, frames[1])
    }

    @Test
    fun rejectsOversizedTransportInput() {
        val framer = AnnexBVideo()
        assertThrows(IllegalArgumentException::class.java) {
            framer.accept(ByteArray(1024 * 1024 + 1))
        }
    }

    @Test
    fun ignoresIncompleteNonVideoPrefix() {
        val framer = AnnexBVideo()
        assertEquals(0, framer.accept(byteArrayOf(0, 0)).size)
        assertEquals(0, framer.finish().size)
    }
}
