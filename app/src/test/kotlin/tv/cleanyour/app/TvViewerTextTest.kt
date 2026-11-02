package tv.cleanyour.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvViewerTextTest {
    @Test fun allowsOnlySafeTextAndEscapesSpacesForAdbInput() {
        assertEquals("TV%sTCL%s4K", validatedRemoteText("TV TCL 4K"))
        assertEquals("wifi_2.4", validatedRemoteText("wifi_2.4"))
    }

    @Test fun rejectsShellOperatorsAndUnboundedText() {
        assertNull(validatedRemoteText(""))
        assertNull(validatedRemoteText("a; rm -rf /"))
        assertNull(validatedRemoteText("a' && reboot"))
        assertNull(validatedRemoteText("a$HOME"))
        assertNull(validatedRemoteText("a".repeat(81)))
    }
}
