package tv.cleanyour.app

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficialUpdatesTest {
    @Test fun permiteAtualizacaoApenasNoPacotePermanenteNaoDepuravel() {
        assertTrue(AppUpdateFeed.supportsOfficialUpdates("tv.cleanyour.app.personal", false))
        assertFalse(AppUpdateFeed.supportsOfficialUpdates("tv.cleanyour.app.personal", true))
        assertFalse(AppUpdateFeed.supportsOfficialUpdates("tv.cleanyour.app.personal.preview", false))
        assertFalse(AppUpdateFeed.supportsOfficialUpdates("tv.cleanyour.app.cleaninstall", false))
        assertFalse(AppUpdateFeed.supportsOfficialUpdates("tv.cleanyour.app", false))
    }

    @Test fun ignoraPacotesDesconhecidosOuVazios() {
        assertFalse(AppUpdateFeed.supportsOfficialUpdates("", false))
        assertFalse(AppUpdateFeed.supportsOfficialUpdates("com.outro.aplicativo", false))
    }
}
