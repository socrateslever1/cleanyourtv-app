package br.espelhotv.protocolo

import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Simula o serviço de controle remoto do Google TV em TLS local para testar
 * o pareamento por código e o envio de teclas de ponta a ponta.
 */
class ControleRemotoTest {
    private val dir = Files.createTempDirectory("tv").toFile()
    private val tvId = Certificado.carregarOuCriar(dir.resolve("tv.p12"), "Google TV falso")
    private val celId = Certificado.carregarOuCriar(dir.resolve("cel.p12"), "Celular")

    private fun servidor(): SSLServerSocket {
        val ctx = ControleRemoto.contextoTls(tvId)
        return (ctx.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .apply { needClientAuth = true }
    }

    // Loopback não é rede privada; o teste usa a função interna via host 127.0.0.1 liberado abaixo.
    @Test fun `pareamento com código certo e errado`() {
        val srv = servidor()
        val tvPub = (tvId.getCertificate(Certificado.ALIAS) as X509Certificate).publicKey as RSAPublicKey
        val celPub = (celId.getCertificate(Certificado.ALIAS) as X509Certificate).publicKey as RSAPublicKey
        // código que a TV "mostra"
        val codigo = (0..255).map { "%02x".format(it) + "c0de" }
            .first { ControleRemoto.segredoDoCodigo(celPub, tvPub, it) != null }
        val esperado = ControleRemoto.segredoDoCodigo(celPub, tvPub, codigo)!!
        val etapas = mutableListOf<Long>()
        val t = thread {
            srv.accept().use { s ->
                val inp = s.inputStream; val out = s.outputStream
                repeat(3) {
                    val m = ProtoMensagem.lerDelimitado(inp)
                    etapas += listOf(10L, 20L, 30L).first { m.tem(it.toInt()) }
                    ProtoEscritor.escreverDelimitado(out, ProtoEscritor().varint(1, 2).varint(2, 200).paraBytes())
                }
                val m = ProtoMensagem.lerDelimitado(inp)
                val ok = MessageDigest.isEqual(m.mensagem(40)!!.bytes(1), esperado)
                ProtoEscritor.escreverDelimitado(out, ProtoEscritor().varint(1, 2).varint(2, if (ok) 200 else 402).paraBytes())
            }
        }
        val p = ControleRemoto.Pareamento(ControleRemoto.contextoTls(celId), "127.0.0.1", "Celular", srv.localPort)
        p.iniciar()
        assertNull(p.confirmar("zz0000")) // formato inválido nem é enviado
        val digital = p.confirmar(codigo)
        p.close(); t.join(); srv.close()
        assertEquals(listOf(10L, 20L, 30L), etapas)
        assertEquals(ControleRemoto.impressaoDigital(tvId.getCertificate(Certificado.ALIAS) as X509Certificate), digital)
    }

    @Test fun `sessão responde configuração, ping e envia tecla`() {
        val srv = servidor()
        val recebidas = mutableListOf<String>()
        val t = thread {
            srv.accept().use { s ->
                val inp = s.inputStream; val out = s.outputStream
                fun env(e: ProtoEscritor) = ProtoEscritor.escreverDelimitado(out, e.paraBytes())
                env(ProtoEscritor().mensagem(1, ProtoEscritor().varint(1, 1)))
                assertNotNull(ProtoMensagem.lerDelimitado(inp).mensagem(1)); recebidas += "config"
                env(ProtoEscritor().mensagem(2, ProtoEscritor().varint(1, 1)))
                assertNotNull(ProtoMensagem.lerDelimitado(inp).mensagem(2)); recebidas += "ativa"
                env(ProtoEscritor().mensagem(8, ProtoEscritor().varint(1, 77)))
                assertEquals(77L, ProtoMensagem.lerDelimitado(inp).mensagem(9)!!.varint(1)); recebidas += "ping"
                val k = ProtoMensagem.lerDelimitado(inp).mensagem(10)!!
                recebidas += "tecla ${k.varint(1)} ${k.varint(2)}"
                recebidas += "link " + ProtoMensagem.lerDelimitado(inp).mensagem(90)!!.texto(1)
            }
        }
        val digital = ControleRemoto.impressaoDigital(tvId.getCertificate(Certificado.ALIAS) as X509Certificate)
        val s = ControleRemoto.Sessao(ControleRemoto.contextoTls(celId), "127.0.0.1", digital, porta = srv.localPort)
        s.conectar()
        val limite = System.currentTimeMillis() + 5000
        while (!s.pronta && System.currentTimeMillis() < limite) Thread.sleep(10)
        assertTrue(s.pronta)
        Thread.sleep(100) // deixa o ping ser respondido
        s.tecla(ControleRemoto.OK)
        s.abrirLink("espelhotv://iniciar")
        t.join(); s.close(); srv.close()
        assertEquals(listOf("config", "ativa", "ping", "tecla 23 3", "link espelhotv://iniciar"), recebidas)
    }
}
