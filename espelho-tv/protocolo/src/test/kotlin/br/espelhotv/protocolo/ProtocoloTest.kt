package br.espelhotv.protocolo

import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocoloTest {
    @Test fun `QR ida e volta`() {
        val p = Pareamento(Pareamento.novoId(), "192.168.0.15", 39762, Pareamento.novaChave(), "Sala de Estar")
        assertEquals(p, Pareamento.lerUri(p.paraUri()))
    }

    @Test fun `QR rejeita IP público, chave ruim e outros esquemas`() {
        val k = Pareamento.novaChave()
        assertNull(Pareamento.lerUri("espelhotv://parear?v=1&id=0a0b0c0d&h=8.8.8.8&p=39762&k=$k&n=TV"))
        assertNull(Pareamento.lerUri("espelhotv://parear?v=1&id=0a0b0c0d&h=10.0.0.2&p=39762&k=abc&n=TV"))
        assertNull(Pareamento.lerUri("https://exemplo.com"))
        assertNotNull(Pareamento.lerUri("espelhotv://parear?v=1&id=0a0b0c0d&h=10.0.0.2&p=39762&k=$k&n=TV"))
    }

    /** Par de sockets locais: (entrada, saída) da TV e do celular. */
    private fun par(): Array<Pair<java.io.InputStream, java.io.OutputStream>> {
        val srv = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val cel = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), srv.localPort)
        val tv = srv.accept(); srv.close()
        return arrayOf(tv.getInputStream() to tv.getOutputStream(), cel.getInputStream() to cel.getOutputStream())
    }

    @Test fun `canal cifrado entrega pacotes em ordem`() {
        val chave = Pareamento.novaChave()
        val (tv, cel) = par()
        var tx: CanalSeguro.Transmissor? = null
        val t = thread { tx = CanalSeguro.aceitar(tv.first, tv.second, chave) }
        val rx = CanalSeguro.conectar(cel.first, cel.second, chave)
        t.join()
        val video = ByteArray(100_000) { it.toByte() }
        thread {
            tx!!.enviar(TipoPacote.VIDEO, 123L, video)
            tx!!.enviar(TipoPacote.AVISO, 0L, "olá".toByteArray())
            tv.second.close()
        }
        val p1 = rx.receber()!!
        assertEquals(TipoPacote.VIDEO, p1.tipo); assertEquals(123L, p1.ptsUs); assertContentEquals(video, p1.dados)
        assertEquals("olá", rx.receber()!!.texto())
        assertNull(rx.receber())
    }

    @Test fun `celular com chave errada é recusado`() {
        val (tv, cel) = par()
        var erro: Throwable? = null
        val t = thread { erro = runCatching { CanalSeguro.aceitar(tv.first, tv.second, Pareamento.novaChave()) }.exceptionOrNull() }
        CanalSeguro.conectar(cel.first, cel.second, Pareamento.novaChave())
        t.join()
        assertTrue(erro is GeneralSecurityException)
    }

    @Test fun `protobuf ida e volta`() {
        val bytes = ProtoEscritor().varint(1, 2).varint(2, 200).mensagem(10,
            ProtoEscritor().texto(1, "atvremote").varint(3, 300_000L)).paraBytes()
        val m = ProtoMensagem.ler(bytes)
        assertEquals(2L, m.varint(1)); assertEquals(200L, m.varint(2))
        assertEquals("atvremote", m.mensagem(10)!!.texto(1)); assertEquals(300_000L, m.mensagem(10)!!.varint(3))
    }

    @Test fun `certificado autoassinado é válido e persiste`() {
        val dir = Files.createTempDirectory("cert").toFile()
        val arq = dir.resolve("id.p12")
        val ks = Certificado.carregarOuCriar(arq, "Espelho TV")
        val cert = ks.getCertificate(Certificado.ALIAS) as java.security.cert.X509Certificate
        cert.verify(cert.publicKey)
        cert.checkValidity()
        val ks2 = Certificado.carregarOuCriar(arq, "Espelho TV")
        assertContentEquals(cert.encoded, ks2.getCertificate(Certificado.ALIAS).encoded)
    }

    @Test fun `segredo do código confere primeiro byte`() {
        val g = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val c = g.generateKeyPair().public as RSAPublicKey
        val s = g.generateKeyPair().public as RSAPublicKey
        // Acha um código válido: escolhe os 4 últimos dígitos e calcula o prefixo.
        var valido: String? = null
        for (prefixo in 0..255) {
            val cod = "%02x".format(prefixo) + "a1b2"
            if (ControleRemoto.segredoDoCodigo(c, s, cod) != null) { valido = cod; break }
        }
        assertNotNull(valido)
        assertNull(ControleRemoto.segredoDoCodigo(c, s, "zzzzzz"))
    }
}
