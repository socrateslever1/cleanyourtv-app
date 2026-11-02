package tv.cleanyour.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * A edição do proprietário é apresentada em português brasileiro,
 * independentemente do idioma geral do Android.
 * Não altera o idioma do dispositivo nem de outros aplicativos.
 */
internal object PtBrContext {
    private val locale = Locale.forLanguageTag("pt-BR")

    fun wrap(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
