package tv.cleanyour.app

import java.io.ByteArrayOutputStream

/**
 * Bounded Annex-B H.264 access-unit assembler.
 *
 * ADB shell-v2 packets are arbitrary byte chunks, never frame boundaries.
 * Only whole access units may be submitted to MediaCodec. This assembler
 * handles start codes spanning chunks and separates consecutive pictures
 * using first_mb_in_slice=0 (Exp-Golomb code begins with bit 1).
 *
 * The TV encoder uses a single H.264 slice per picture in the common case.
 * Multi-slice pictures are assembled until the first slice of the next one.
 */
internal class AnnexBVideo {
    private val pending = ByteArrayOutputStream()
    private val picture = ByteArrayOutputStream()
    private var hasSlice = false

    private companion object {
        const val MAX_NAL = 1024 * 1024
        const val MAX_PICTURE = 2 * 1024 * 1024
        const val MAX_CHUNK = 1024 * 1024
    }

    fun accept(chunk: ByteArray): List<ByteArray> {
        require(chunk.size <= MAX_CHUNK) { "video transport chunk too large" }
        if (pending.size() + chunk.size > MAX_NAL) {
            throw IllegalStateException("H.264 NAL exceeded 1 MiB")
        }
        pending.write(chunk)
        val src = pending.toByteArray()
        val out = ArrayList<ByteArray>()
        val first = findStart(src, 0)
        if (first < 0) {
            if (src.size > 3) {
                pending.reset()
                pending.write(src, src.size - 3, 3)
            }
            return out
        }
        var at = first
        while (true) {
            val next = findStart(src, at + startCodeSize(src, at))
            if (next < 0) break
            appendNal(src.copyOfRange(at, next), out)
            at = next
        }
        pending.reset()
        pending.write(src, at, src.size - at)
        if (pending.size() > MAX_NAL) throw IllegalStateException("H.264 NAL exceeded 1 MiB")
        return out
    }

    fun finish(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val rest = pending.toByteArray()
        pending.reset()
        if (rest.isNotEmpty()) appendNal(rest, out)
        emit(out)
        return out
    }

    private fun appendNal(nal: ByteArray, out: MutableList<ByteArray>) {
        val sc = startCodeSize(nal, 0)
        if (sc == 0 || nal.size <= sc) return
        val type = nal[sc].toInt() and 31
        val slice = type == 1 || type == 5
        val firstSlice = slice && nal.size > sc + 1 &&
            (nal[sc + 1].toInt() and 0x80) != 0
        if (hasSlice && (firstSlice || type in setOf(6, 7, 8, 9))) emit(out)
        if (picture.size() + nal.size > MAX_PICTURE) {
            throw IllegalStateException("H.264 access unit exceeded 2 MiB")
        }
        picture.write(nal)
        if (slice) hasSlice = true
    }

    private fun emit(out: MutableList<ByteArray>) {
        if (hasSlice && picture.size() > 0) out += picture.toByteArray()
        picture.reset()
        hasSlice = false
    }

    private fun findStart(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 <= b.size) {
            if (b[i] == 0.toByte() && b[i + 1] == 0.toByte()) {
                if (b[i + 2] == 1.toByte()) return i
                if (i + 3 < b.size && b[i + 2] == 0.toByte() &&
                    b[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeSize(b: ByteArray, index: Int): Int {
        if (index + 3 <= b.size && b[index] == 0.toByte() &&
            b[index + 1] == 0.toByte() && b[index + 2] == 1.toByte()) return 3
        if (index + 4 <= b.size && b[index] == 0.toByte() &&
            b[index + 1] == 0.toByte() && b[index + 2] == 0.toByte() &&
            b[index + 3] == 1.toByte()) return 4
        return 0
    }
}
