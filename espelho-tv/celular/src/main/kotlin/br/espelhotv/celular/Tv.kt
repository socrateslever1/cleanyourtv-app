package br.espelhotv.celular

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import br.espelhotv.protocolo.Certificado
import br.espelhotv.protocolo.ControleRemoto
import br.espelhotv.protocolo.Pareamento
import br.espelhotv.protocolo.Rede
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** O que o celular lembra da TV. */
object Armazenamento {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("espelhotv", Context.MODE_PRIVATE)

    fun pareamento(ctx: Context): Pareamento? =
        prefs(ctx).getString("pareamento", null)?.let(Pareamento::lerUri)

    fun salvar(ctx: Context, p: Pareamento) {
        val anterior = pareamento(ctx)
        prefs(ctx).edit().putString("pareamento", p.paraUri()).apply()
        // Outra TV: o controle remoto precisa ser pareado de novo.
        if (anterior != null && anterior.id != p.id) esquecerControle(ctx)
    }

    /** A TV mudou de IP (roteador). Guarda o novo. */
    fun atualizarHost(ctx: Context, host: String) {
        val p = pareamento(ctx) ?: return
        if (p.host != host && Rede.ipv4Privado(host)) salvar(ctx, p.copy(host = host))
    }

    fun digitalControle(ctx: Context): String? = prefs(ctx).getString("controle", null)
    fun salvarControle(ctx: Context, digital: String) = prefs(ctx).edit().putString("controle", digital).apply()
    fun esquecerControle(ctx: Context) = prefs(ctx).edit().remove("controle").apply()

    fun esquecerTudo(ctx: Context) = prefs(ctx).edit().clear().apply()
}

/** Acha a TV na rede pelo id do QR, quando o IP guardado não responde mais. */
object Descoberta {
    private const val TIPO = "_espelhotv._tcp"

    fun procurar(ctx: Context, id: String, esperaMs: Long = 5_000): String? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val trava = wifi?.createMulticastLock("espelhotv")?.apply { setReferenceCounted(false); acquire() }
        val achou = CountDownLatch(1)
        var host: String? = null
        val ouvinte = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { achou.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (!s.serviceName.endsWith(id)) return
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val ip = s.host?.hostAddress
                        if (ip != null && Rede.ipv4Privado(ip)) { host = ip; achou.countDown() }
                    }
                })
            }
        }
        return try {
            nsd.discoverServices(TIPO, NsdManager.PROTOCOL_DNS_SD, ouvinte)
            achou.await(esperaMs, TimeUnit.MILLISECONDS)
            host
        } catch (_: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(ouvinte) }
            runCatching { trava?.release() }
        }
    }
}

/**
 * Controle remoto do Google TV, compartilhado pelas telas. Tudo roda numa
 * fila em segundo plano; os botões só enfileiram.
 */
object ControleTv {
    private val fila = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var sessao: ControleRemoto.Sessao? = null
    @Volatile private var tls: SSLContext? = null
    @Volatile var ultimoErro: String? = null
        private set
    var aoMudar: (() -> Unit)? = null

    val conectado get() = sessao?.pronta == true

    private fun avisar() = principal.post { aoMudar?.invoke() }

    fun contexto(ctx: Context): SSLContext = tls ?: synchronized(this) {
        tls ?: ControleRemoto.contextoTls(
            Certificado.carregarOuCriar(File(ctx.filesDir, "controle.p12"), "Espelho TV ${Build.MODEL}"),
        ).also { tls = it }
    }

    fun conectar(ctx: Context) = fila.execute { garantir(ctx.applicationContext) }

    private fun garantir(ctx: Context): Boolean {
        if (sessao?.pronta == true) return true
        val digital = Armazenamento.digitalControle(ctx) ?: return false
        val host = Armazenamento.pareamento(ctx)?.host ?: return false
        sessao?.close()
        return try {
            val s = ControleRemoto.Sessao(contexto(ctx), host, digital, aoCair = { erro ->
                sessao = null
                ultimoErro = erro?.message
                avisar()
            })
            s.conectar()
            sessao = s
            val limite = System.currentTimeMillis() + 4_000
            while (!s.pronta && System.currentTimeMillis() < limite) Thread.sleep(25)
            ultimoErro = if (s.pronta) null else "a TV não respondeu ao controle"
            avisar()
            s.pronta
        } catch (e: Exception) {
            ultimoErro = e.message
            avisar()
            false
        }
    }

    fun tecla(ctx: Context, codigo: Int) = fila.execute {
        val app = ctx.applicationContext
        if (!garantir(app)) return@execute
        runCatching { sessao?.tecla(codigo) }.onFailure { sessao?.close(); sessao = null; avisar() }
    }

    /** Abre o Espelho TV na TV (pede a autorização de captura lá). */
    fun abrirAppNaTv(ctx: Context) = fila.execute {
        val app = ctx.applicationContext
        if (!garantir(app)) return@execute
        runCatching { sessao?.abrirLink("espelhotv://iniciar") }
    }

    fun desconectar() = fila.execute { sessao?.close(); sessao = null }
}
