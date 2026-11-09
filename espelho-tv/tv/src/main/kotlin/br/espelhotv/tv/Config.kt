package br.espelhotv.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import br.espelhotv.protocolo.Pareamento
import br.espelhotv.protocolo.Rede
import java.net.Inet4Address
import java.net.NetworkInterface

/** Identidade da TV e segredo do QR. Gerados uma vez; "Novo QR" troca o segredo. */
object Config {
    private const val PREFS = "espelhotv"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun id(ctx: Context): String = prefs(ctx).getString("id", null)
        ?: Pareamento.novoId().also { prefs(ctx).edit().putString("id", it).apply() }

    @Synchronized
    fun chave(ctx: Context): String = prefs(ctx).getString("chave", null)
        ?: Pareamento.novaChave().also { prefs(ctx).edit().putString("chave", it).apply() }

    /** Invalida todos os celulares pareados. */
    @Synchronized
    fun novaChave(ctx: Context) {
        prefs(ctx).edit().putString("chave", Pareamento.novaChave()).apply()
    }

    fun nome(): String = android.os.Build.MODEL?.takeIf { it.isNotBlank() }?.take(40) ?: "TV"

    /** Primeiro IPv4 de rede local privada (Wi-Fi ou cabo). */
    fun ipLocal(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { Rede.ipv4Privado(it) }
    }.getOrNull()

    fun pareamento(ctx: Context): Pareamento? {
        val ip = ipLocal() ?: return null
        return Pareamento(id(ctx), ip, Pareamento.PORTA_PADRAO, chave(ctx), nome())
    }
}

/** Estado da transmissão para a tela da TV. */
object Estado {
    const val PARADO = 0
    const val TRANSMITINDO_SEM_CELULAR = 1
    const val TRANSMITINDO_COM_CELULAR = 2
    const val ERRO = 3

    @Volatile var atual = PARADO
        private set
    @Volatile var detalhe: String? = null
        private set

    private val principal = Handler(Looper.getMainLooper())
    private val ouvintes = mutableSetOf<() -> Unit>()

    fun publicar(estado: Int, texto: String? = null) {
        atual = estado
        detalhe = texto
        principal.post { ouvintes.toList().forEach { it() } }
    }

    fun ouvir(f: () -> Unit) { ouvintes += f }
    fun parar(f: () -> Unit) { ouvintes -= f }
}
