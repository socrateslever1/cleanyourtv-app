package br.espelhotv.protocolo

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Tipos de pacote enviados da TV para o celular. */
object TipoPacote {
    /** payload: largura(int) altura(int) + SPS/PPS em Annex-B. */
    const val VIDEO_CONFIG: Byte = 1
    /** payload: unidade de acesso H.264 em Annex-B. */
    const val VIDEO: Byte = 2
    /** payload: taxa de amostragem(int) canais(byte). Áudio PCM 16 bits little-endian. */
    const val AUDIO_CONFIG: Byte = 3
    /** payload: amostras PCM 16 bits little-endian intercaladas. */
    const val AUDIO: Byte = 4
    /** payload: texto UTF-8 curto para mostrar ao usuário (ex.: "TV sem áudio capturável"). */
    const val AVISO: Byte = 5
}

class Pacote(val tipo: Byte, val ptsUs: Long, val dados: ByteArray) {
    fun texto(): String = String(dados, StandardCharsets.UTF_8)
}

/**
 * Canal TV -> celular, em rede local.
 *
 * Aperto de mão: a TV envia "ETV1" + desafio de 16 bytes; o celular responde
 * HMAC-SHA256(segredo, rótulo || desafio). Quem não tem o segredo do QR não
 * entra. Depois, cada pacote vai cifrado em AES-256-GCM com chave derivada do
 * segredo e do desafio, e nonce = 8 bytes do desafio + contador. Assim o
 * celular também só aceita imagens de quem conhece o segredo.
 */
object CanalSeguro {
    const val TAMANHO_MAXIMO = 4 * 1024 * 1024
    private val MAGICO = byteArrayOf('E'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private val ROTULO_AUTH = "espelhotv-auth-v1".toByteArray(StandardCharsets.US_ASCII)
    private val ROTULO_FLUXO = "espelhotv-stream-v1".toByteArray(StandardCharsets.US_ASCII)

    /** Lado da TV. Lança exceção se o celular não provar que tem o segredo. */
    fun aceitar(entrada: InputStream, saida: OutputStream, chaveHex: String): Transmissor {
        val segredo = Hex.decodificar(chaveHex)
        val desafio = ByteArray(16).also { SecureRandom().nextBytes(it) }
        saida.write(MAGICO + desafio)
        saida.flush()
        val resposta = ByteArray(32)
        DataInputStream(entrada).readFully(resposta)
        if (!MessageDigest.isEqual(resposta, hmac(segredo, ROTULO_AUTH + desafio)))
            throw GeneralSecurityException("celular não autorizado")
        val chave = hmac(segredo, ROTULO_FLUXO + desafio)
        segredo.fill(0)
        return Transmissor(saida, chave, desafio.copyOf(8))
    }

    /** Lado do celular. */
    fun conectar(entrada: InputStream, saida: OutputStream, chaveHex: String): Receptor {
        val dados = DataInputStream(entrada)
        val cabecalho = ByteArray(20)
        dados.readFully(cabecalho)
        if (!cabecalho.copyOf(4).contentEquals(MAGICO)) throw IOException("não é uma TV com Espelho TV")
        val desafio = cabecalho.copyOfRange(4, 20)
        val segredo = Hex.decodificar(chaveHex)
        saida.write(hmac(segredo, ROTULO_AUTH + desafio))
        saida.flush()
        val chave = hmac(segredo, ROTULO_FLUXO + desafio)
        segredo.fill(0)
        return Receptor(dados, chave, desafio.copyOf(8))
    }

    private fun hmac(chave: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(chave, "HmacSHA256"))
            doFinal(msg)
        }

    internal fun nonce(prefixo: ByteArray, seq: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefixo).putInt(seq).array()

    class Transmissor internal constructor(
        saida: OutputStream,
        chave: ByteArray,
        private val prefixo: ByteArray,
    ) {
        private val saida = DataOutputStream(saida)
        private val chave = SecretKeySpec(chave, "AES")
        private var seq = 0

        /** Seguro para chamar de várias threads (vídeo e áudio). */
        @Synchronized
        fun enviar(tipo: Byte, ptsUs: Long, dados: ByteArray, offset: Int = 0, tamanho: Int = dados.size) {
            if (tamanho + 9 > TAMANHO_MAXIMO - 16) throw IOException("pacote grande demais")
            if (seq == Int.MAX_VALUE) throw IOException("contador esgotado")
            val claro = ByteBuffer.allocate(9 + tamanho).put(tipo).putLong(ptsUs).put(dados, offset, tamanho).array()
            val cifra = Cipher.getInstance("AES/GCM/NoPadding")
            cifra.init(Cipher.ENCRYPT_MODE, chave, GCMParameterSpec(128, nonce(prefixo, seq++)))
            val cifrado = cifra.doFinal(claro)
            saida.writeInt(cifrado.size)
            saida.write(cifrado)
            saida.flush()
        }
    }

    class Receptor internal constructor(
        private val entrada: DataInputStream,
        chave: ByteArray,
        private val prefixo: ByteArray,
    ) {
        private val chave = SecretKeySpec(chave, "AES")
        private var seq = 0

        /** Retorna null quando a TV encerra a conexão. */
        fun receber(): Pacote? {
            val tamanho = try { entrada.readInt() } catch (_: EOFException) { return null }
            if (tamanho !in 25..TAMANHO_MAXIMO) throw IOException("pacote com tamanho inválido")
            val cifrado = ByteArray(tamanho)
            entrada.readFully(cifrado)
            if (seq == Int.MAX_VALUE) throw IOException("contador esgotado")
            val cifra = Cipher.getInstance("AES/GCM/NoPadding")
            cifra.init(Cipher.DECRYPT_MODE, chave, GCMParameterSpec(128, nonce(prefixo, seq++)))
            val claro = ByteBuffer.wrap(cifra.doFinal(cifrado))
            val tipo = claro.get()
            val pts = claro.getLong()
            val dados = ByteArray(claro.remaining()).also { claro.get(it) }
            return Pacote(tipo, pts, dados)
        }
    }
}
