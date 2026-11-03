package tv.cleanyour.adb.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureHeaderTest {
    @Test
    fun acceptsPngMagicAndMinimumHeader() {
        val png = byteArrayOf(
            137.toByte(), 80, 78, 71, 13, 10, 26, 10,
            0, 0, 0, 13, 73, 72, 68, 82,
            0, 0, 5, 0, 0, 0, 2, 208.toByte()
        )
        assertTrue(validScreenPng(png))
    }

    @Test
    fun rejectsEmptyTruncatedOrTextOutput() {
        assertFalse(validScreenPng(byteArrayOf()))
        assertFalse(validScreenPng(byteArrayOf(137.toByte(), 80, 78, 71)))
        assertFalse(validScreenPng("Permission denied".toByteArray()))
    }

    @Test
    fun rejectsIncorrectMagicEvenWithHeaderLength() {
        val wrong = ByteArray(32)
        wrong[0] = 137.toByte()
        wrong[1] = 80
        wrong[2] = 78
        wrong[3] = 71
        wrong[4] = 0
        assertFalse(validScreenPng(wrong))
    }
}
