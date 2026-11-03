package tv.cleanyour.feature.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.ui.design.theme.CytTheme

// Issues #655, #643 and #768: after `adb pair` and `adb connect` the computer lists the TV twice
// (the pairing and the connect address), so a plain `adb tcpip 5555` stops with "more than one
// device". The third step selects the connected address; the final steps reconnect and verify.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PairingStepsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test fun theFullSequenceSelectsTheConnectionAndVerifiesTcpPort() {
        rule.setContent { CytTheme { Column { PairingStepsContent() } } }
        rule.onNodeWithText("adb pair ADDRESS:PAIR_PORT", substring = true).assertExists()
        rule.onNodeWithText("adb connect ADDRESS:CONNECT_PORT", substring = true).assertExists()
        rule.onNodeWithText("adb -s ADDRESS:CONNECT_PORT tcpip 5555", substring = true).assertExists()
        rule.onNodeWithText("adb connect ADDRESS:5555", substring = true).assertExists()
        rule.onNodeWithText("adb devices -l", substring = true).assertExists()
        rule.onNodeWithText("The PC pairing does not authorize the phone", substring = true).assertExists()
        rule.onNodeWithText("legacy ADB without the transport encryption", substring = true).assertExists()
    }
}
