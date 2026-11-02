package tv.cleanyour.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Garante que a interface use pt-BR sem mudar o idioma do sistema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PtBrContextTest {
    @Test fun configuraIdiomaBrasileiroSemAlterarContextoOriginal() {
        val original = ApplicationProvider.getApplicationContext<Context>()
        val before = original.resources.configuration.locales[0]
        val translated = PtBrContext.wrap(original)

        assertEquals("pt-BR", translated.resources.configuration.locales[0].toLanguageTag())
        assertEquals(before, original.resources.configuration.locales[0])
    }
}
