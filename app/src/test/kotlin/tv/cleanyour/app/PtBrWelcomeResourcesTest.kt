package tv.cleanyour.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import tv.cleanyour.feature.connect.R as ConnectR

/** Os textos principais devem existir no código sem depender de tradução online. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PtBrWelcomeResourcesTest {
    @Test fun telaInicialTemTraducoesNativasEmPtBr() {
        val context = PtBrContext.wrap(ApplicationProvider.getApplicationContext<Application>())
        assertEquals("Sua TV, mais organizada.", context.getString(ConnectR.string.welcome_title))
        assertEquals("Começar", context.getString(ConnectR.string.welcome_start))
        assertEquals("Já configurei uma TV", context.getString(ConnectR.string.welcome_returning))
        assertEquals("Qual TV deseja configurar?", context.getString(ConnectR.string.picker_title))
        assertEquals("Nenhuma TV encontrada. A busca continua.",
            context.getString(ConnectR.string.welcome_none_yet))
    }
}
