package tv.cleanyour.core.flows

/**
 * Pure, transport-independent contract for TV-to-phone viewing.
 * No video or input is sent until an explicitly authorized session is connected.
 */
sealed interface ViewerStatus {
    data object Idle : ViewerStatus
    data object Connecting : ViewerStatus
    data object AwaitingPermission : ViewerStatus
    data class Streaming(val width: Int, val height: Int) : ViewerStatus {
        init {
            require(width > 0 && height > 0) { "Invalid remote frame size" }
        }
    }
    data class Failed(val reason: String) : ViewerStatus
}

enum class RemoteKey(val androidKeyCode: Int) {
    UP(19), DOWN(20), LEFT(21), RIGHT(22), SELECT(23),
    BACK(4), HOME(3), VOLUME_UP(24), VOLUME_DOWN(25)
}

data class RemotePoint(val x: Int, val y: Int)

/**
 * Maps a phone touch inside the letterboxed video bounds to TV pixels.
 * Returns null for touches on surrounding controls or black bars.
 */
fun mapRemoteTouch(
    touchX: Float,
    touchY: Float,
    viewWidth: Int,
    viewHeight: Int,
    tvWidth: Int,
    tvHeight: Int,
): RemotePoint? {
    if (viewWidth <= 0 || viewHeight <= 0 || tvWidth <= 0 || tvHeight <= 0) return null
    if (!touchX.isFinite() || !touchY.isFinite()) return null
    val scale = minOf(viewWidth.toDouble() / tvWidth, viewHeight.toDouble() / tvHeight)
    val imageWidth = tvWidth * scale
    val imageHeight = tvHeight * scale
    val left = (viewWidth - imageWidth) / 2
    val top = (viewHeight - imageHeight) / 2
    if (touchX < left || touchY < top || touchX >= left + imageWidth || touchY >= top + imageHeight) return null
    val x = ((touchX - left) / scale).toInt().coerceIn(0, tvWidth - 1)
    val y = ((touchY - top) / scale).toInt().coerceIn(0, tvHeight - 1)
    return RemotePoint(x, y)
}

/** Never execute untrusted or disconnected remote-control requests. */
fun canSendRemoteInput(status: ViewerStatus, authorized: Boolean): Boolean =
    authorized && status is ViewerStatus.Streaming
