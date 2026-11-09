package br.espelhotv.protocolo

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Cliente do protocolo de controle remoto que o próprio Google TV oferece
 * (o mesmo usado pelo app Google TV no celular). Não precisa de nada
 * instalado ou ativado na TV: só confirmar o código que ela mostra.
 *
 * Porta 6467: pareamento. Porta 6466: comandos. Ambas em TLS com o
 * certificado do celular. Mensagens protobuf com tamanho em varint.
 */
object ControleRemoto {
    const val PORTA_PAREAMENTO = 6467
    const val PORTA_COMANDOS = 6466

    // Códigos de tecla do Android (KeyEvent.KEYCODE_*).
    const val CIMA = 19
    const val BAIXO = 20
    const val ESQUERDA = 21
    const val DIREITA = 22
    const val OK = 23
    const val VOLTAR = 4
    const val INICIO = 3
    const val VOLUME_MAIS = 24
    const val VOLUME_MENOS = 25
    const val MUDO = 164
    const val PLAY_PAUSE = 85
    const val LIGA_DESLIGA = 26
    const val CANAL_MAIS = 166
    const val CANAL_MENOS = 167
    const val AVANCAR = 90
    const val VOLTAR_MIDIA = 89

    private const val STATUS_OK = 200L
    private const val CODIGO_CONFIG = 622

    fun contextoTls(identidade: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identidade, Certificado.senha())
        // O Google TV usa certificado autoassinado. Confiamos no primeiro
        // contato (pareamento com código na tela) e depois fixamos a impressão
        // digital do certificado da TV.
        val confiarTodos = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, t: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(confiarTodos), null) }
    }

    fun impressaoDigital(cert: X509Certificate): String =
        Hex.codificar(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

    private fun abrir(ctx: SSLContext, host: String, porta: Int): SSLSocket {
        require(Rede.ipv4Privado(host) || host == "127.0.0.1") { "a TV precisa estar em rede local privada" }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, porta), 5_000)
        s.soTimeout = 15_000
        s.tcpNoDelay = true
        s.startHandshake()
        return s
    }

    /**
     * Hash que prova à TV que o usuário digitou o código certo:
     * SHA-256(módulo e expoente do celular, módulo e expoente da TV, código[2:]).
     * O primeiro byte do hash deve ser igual aos dois primeiros dígitos do código.
     */
    fun segredoDoCodigo(cliente: RSAPublicKey, servidor: RSAPublicKey, codigo: String): ByteArray? {
        val c = codigo.trim().lowercase()
        if (c.length != 6 || !c.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(semSinal(cliente.modulus.toByteArray()))
        sha.update(semSinal(cliente.publicExponent.toByteArray()))
        sha.update(semSinal(servidor.modulus.toByteArray()))
        sha.update(semSinal(servidor.publicExponent.toByteArray()))
        sha.update(Hex.decodificar(c.substring(2)))
        val hash = sha.digest()
        return if ((hash[0].toInt() and 0xff) == c.substring(0, 2).toInt(16)) hash else null
    }

    private fun semSinal(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        return b.copyOfRange(i, b.size)
    }

    // ---------- Pareamento ----------

    private fun msgPareamento(campo: Int, corpo: ProtoEscritor) =
        ProtoEscritor().varint(1, 2).varint(2, STATUS_OK.toInt()).mensagem(campo, corpo).paraBytes()

    private fun codificacaoHex6() = ProtoEscritor().varint(1, 3).varint(2, 6) // HEXADECIMAL, 6 símbolos

    /** Sessão de pareamento: [iniciar] faz a TV mostrar o código; [confirmar] envia o código digitado. */
    class Pareamento(
        private val ctx: SSLContext,
        private val host: String,
        private val nomeCelular: String,
        private val porta: Int = PORTA_PAREAMENTO,
    ) : Closeable {
        private var socket: SSLSocket? = null

        fun iniciar() {
            val s = abrir(ctx, host, porta)
            socket = s
            val out = s.outputStream
            val inp = s.inputStream
            fun troca(msg: ByteArray) {
                ProtoEscritor.escreverDelimitado(out, msg)
                val resp = ProtoMensagem.lerDelimitado(inp)
                if (resp.varint(2) != STATUS_OK) throw IOException("a TV recusou o pareamento")
            }
            troca(msgPareamento(10, ProtoEscritor().texto(1, "atvremote").texto(2, nomeCelular)))
            troca(msgPareamento(20, ProtoEscritor().mensagem(1, codificacaoHex6()).varint(3, 1)))
            troca(msgPareamento(30, ProtoEscritor().mensagem(1, codificacaoHex6()).varint(2, 1)))
            // A TV agora mostra o código de 6 caracteres. A conexão fica aberta.
            s.soTimeout = 0
        }

        /** Retorna a impressão digital do certificado da TV, ou null se o código estiver errado. */
        fun confirmar(codigo: String): String? {
            val s = socket ?: throw IllegalStateException("pareamento não iniciado")
            val cliente = s.session.localCertificates?.firstOrNull() as? X509Certificate
                ?: throw IOException("certificado do celular ausente")
            val servidor = s.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IOException("certificado da TV ausente")
            val segredo = segredoDoCodigo(
                cliente.publicKey as RSAPublicKey,
                servidor.publicKey as RSAPublicKey,
                codigo,
            ) ?: return null
            s.soTimeout = 15_000
            ProtoEscritor.escreverDelimitado(s.outputStream, msgPareamento(40, ProtoEscritor().bytes(1, segredo)))
            val resp = ProtoMensagem.lerDelimitado(s.inputStream)
            if (resp.varint(2) != STATUS_OK) return null
            return impressaoDigital(servidor)
        }

        override fun close() { runCatching { socket?.close() } }
    }

    // ---------- Comandos ----------

    /**
     * Conexão de comandos. Responde sozinha às mensagens de configuração e
     * "ping" da TV numa thread própria; [tecla] e [abrirLink] podem ser
     * chamados de qualquer thread.
     */
    class Sessao(
        private val ctx: SSLContext,
        private val host: String,
        private val impressaoEsperada: String?,
        private val porta: Int = PORTA_COMANDOS,
        private val aoCair: (Throwable?) -> Unit = {},
    ) : Closeable {
        @Volatile private var socket: SSLSocket? = null
        @Volatile var pronta = false
            private set

        fun conectar() {
            val s = abrir(ctx, host, porta)
            val cert = s.session.peerCertificates.firstOrNull() as? X509Certificate
            if (impressaoEsperada != null && (cert == null || impressaoDigital(cert) != impressaoEsperada)) {
                s.close()
                throw IOException("o certificado da TV mudou; pareie o controle de novo")
            }
            s.soTimeout = 0
            socket = s
            Thread({ ler(s) }, "espelhotv-controle").apply { isDaemon = true }.start()
        }

        private fun ler(s: SSLSocket) {
            var erro: Throwable? = null
            try {
                val inp = s.inputStream
                while (!s.isClosed) {
                    val m = ProtoMensagem.lerDelimitado(inp)
                    when {
                        m.tem(1) -> enviar(
                            ProtoEscritor().mensagem(1, ProtoEscritor().varint(1, CODIGO_CONFIG).mensagem(2,
                                ProtoEscritor().texto(1, "Espelho TV").texto(2, "Espelho TV").varint(3, 1)
                                    .texto(4, "1").texto(5, "br.espelhotv.celular").texto(6, "1.0.0")))
                        )
                        m.tem(2) -> {
                            enviar(ProtoEscritor().mensagem(2, ProtoEscritor().varint(1, CODIGO_CONFIG)))
                            pronta = true
                        }
                        m.tem(8) -> {
                            val v = m.mensagem(8)?.varint(1) ?: 0L
                            enviar(ProtoEscritor().mensagem(9, ProtoEscritor().varint(1, v)))
                        }
                        m.tem(3) -> throw IOException("a TV recusou o controle; pareie de novo")
                    }
                }
            } catch (t: Throwable) {
                erro = t
            } finally {
                pronta = false
                runCatching { s.close() }
                if (socket === s) aoCair(erro)
            }
        }

        @Synchronized
        private fun enviar(msg: ProtoEscritor) {
            val s = socket ?: throw IOException("controle desconectado")
            ProtoEscritor.escreverDelimitado(s.outputStream, msg.paraBytes())
        }

        /** Aperto curto de uma tecla do controle. */
        fun tecla(codigo: Int) {
            enviar(ProtoEscritor().mensagem(10, ProtoEscritor().varint(1, codigo).varint(2, 3)))
        }

        /** Abre um link na TV (ex.: espelhotv://iniciar abre o app da TV). */
        fun abrirLink(link: String) {
            enviar(ProtoEscritor().mensagem(90, ProtoEscritor().texto(1, link)))
        }

        override fun close() {
            val s = socket
            socket = null
            pronta = false
            runCatching { s?.close() }
        }
    }
}
