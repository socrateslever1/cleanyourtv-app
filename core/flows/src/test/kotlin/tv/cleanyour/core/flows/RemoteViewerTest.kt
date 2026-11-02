package tv.cleanyour.core.flows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RemoteViewerTest {
    @Test fun landscapeImageInPortraitViewUsesLetterboxing() {
        assertEquals(RemotePoint(960, 540), mapRemoteTouch(200f, 400f, 400, 800, 1920, 1080))
        assertNull(mapRemoteTouch(200f, 100f, 400, 800, 1920, 1080))
    }

    @Test fun outsideAndInvalidInputsAreRejected() {
        assertNull(mapRemoteTouch(-1f, 5f, 100, 100, 100, 100))
        assertNull(mapRemoteTouch(Float.NaN, 5f, 100, 100, 100, 100))
        assertNull(mapRemoteTouch(1f, 1f, 0, 100, 100, 100))
        assertNull(mapRemoteTouch(100f, 10f, 100, 100, 100, 100))
    }

    @Test fun authorizationAndLiveStreamAreRequired() {
        assertFalse(canSendRemoteInput(ViewerStatus.Idle, true))
        assertFalse(canSendRemoteInput(ViewerStatus.Connecting, true))
        assertFalse(canSendRemoteInput(ViewerStatus.Streaming(1920, 1080), false))
        assertTrue(canSendRemoteInput(ViewerStatus.Streaming(1920, 1080), true))
    }

    @Test fun invalidFramesAreRejected() {
        assertFailsWith<IllegalArgumentException> { ViewerStatus.Streaming(0, 1080) }
    }

    @Test fun remoteKeysUseAndroidKeycodes() {
        assertEquals(3, RemoteKey.HOME.androidKeyCode)
        assertEquals(4, RemoteKey.BACK.androidKeyCode)
        assertEquals(23, RemoteKey.SELECT.androidKeyCode)
    }
}
