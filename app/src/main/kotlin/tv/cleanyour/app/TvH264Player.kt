package tv.cleanyour.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.session.AdbTvSession

/**
 * Decodes the built-in Android screenrecord Annex-B stream onto a Surface.
 * The TV sends H.264 through ADB and the PHONE owns the MediaCodec decoder.
 * No file is written on either device. No camera, microphone or recording
 * permission is requested. Protected DRM surfaces remain inaccessible.
 *
 * Not a replacement for a future scrcpy/Bridge pipeline: screenrecord has a
 * 180-second session limit and some Android TV firmware disables its encoder.
 */
internal object TvH264Player {
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val MAX_VIDEO_INPUT = 2 * 1024 * 1024

    suspend fun play(
        tv: AdbTvSession,
        surface: Surface,
        onFirstFrame: () -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        var decoder: MediaCodec? = null
        try {
            if (!surface.isValid) return@withContext false
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder = codec
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec.configure(format, surface, null, 0)
            codec.start()

            val packets = AnnexBVideo()
            var receivedFrames = 0
            var queuedFrames = 0L

            fun drain(waitUs: Long = 0): Int {
                var rendered = 0
                val info = MediaCodec.BufferInfo()
                for (iteration in 0 until 16) {
                    val index = codec.dequeueOutputBuffer(info, if (iteration == 0) waitUs else 0)
                    if (index >= 0) {
                        val hasImage = info.size > 0
                        codec.releaseOutputBuffer(index, hasImage)
                        if (hasImage) rendered++
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                }
                return rendered
            }

            fun queuePicture(bytes: ByteArray) {
                require(bytes.size in 1..MAX_VIDEO_INPUT)
                val timestampUs = queuedFrames * 33_333
                queuedFrames++
                var pos = 0
                while (pos < bytes.size) {
                    var inputIndex = codec.dequeueInputBuffer(20_000)
                    if (inputIndex < 0) {
                        receivedFrames += drain(20_000)
                        inputIndex = codec.dequeueInputBuffer(20_000)
                    }
                    if (inputIndex < 0) throw IllegalStateException("Video decoder input unavailable")
                    val input = codec.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Video decoder returned null input buffer")
                    input.clear()
                    val count = minOf(input.remaining(), bytes.size - pos)
                    if (count <= 0) throw IllegalStateException("Video decoder input size is zero")
                    input.put(bytes, pos, count)
                    pos += count
                    val flags = if (pos < bytes.size) MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                    codec.queueInputBuffer(inputIndex, 0, count, timestampUs, flags)
                }
                receivedFrames += drain(10_000)
                if (receivedFrames > 0) onFirstFrame()
            }

            val completed = tv.streamScreenH264 { chunk ->
                packets.accept(chunk).forEach(::queuePicture)
            }
            packets.finish().forEach(::queuePicture)
            receivedFrames += drain(40_000)
            if (receivedFrames > 0) onFirstFrame()
            completed && receivedFrames > 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            decoder?.let { codec ->
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        }
    }
}
