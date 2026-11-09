package br.espelhotv.protocolo

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Dados que a TV mostra no QR code e o celular guarda depois de ler.
 *
 * Formato: `espelhotv://parear?v=1&id=<id>&h=<ip>&p=<porta>&k=<chave hex>&n=<nome>`.
 * A chave nunca trafega pela rede: só pelo QR, lido pela câmera do celular.
 */
data class Pareamento(
    val id: String,
    val host: String,
    val porta: Int,
    val chaveHex: String,
    val nome: String,
) {
    init {
        require(ID.matches(id)) { "id inválido" }
        require(Rede.ipv4Privado(host)) { "a TV precisa estar em rede local privada" }
        require(porta in 1024..65535) { "porta inválida" }
        require(Hex.chaveValida(chaveHex)) { "chave inválida" }
        require(nome.length in 1..60) { "nome inválido" }
    }

    fun paraUri(): String =
        "$ESQUEMA://parear?v=1&id=$id&h=$host&p=$porta&k=$chaveHex&n=" +
            URLEncoder.encode(nome, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        const val ESQUEMA = "espelhotv"
        const val PORTA_PADRAO = 39762
        private val ID = Regex("[0-9a-f]{8}")

        fun novoId(): String = Hex.codificar(ByteArray(4).also { SecureRandom().nextBytes(it) })

        fun novaChave(): String = Hex.codificar(ByteArray(32).also { SecureRandom().nextBytes(it) })

        /** Retorna null para qualquer coisa que não seja um QR válido do Espelho TV. */
        fun lerUri(texto: String): Pareamento? = runCatching {
            val uri = URI(texto.trim())
            if (uri.scheme != ESQUEMA || uri.host != "parear") return null
            val q = (uri.rawQuery ?: return null).split('&').associate {
                val i = it.indexOf('=')
                if (i < 0) it to "" else it.substring(0, i) to
                    URLDecoder.decode(it.substring(i + 1), StandardCharsets.UTF_8)
            }
            if (q["v"] != "1") return null
            Pareamento(
                id = q.getValue("id"),
                host = q.getValue("h"),
                porta = q.getValue("p").toInt(),
                chaveHex = q.getValue("k"),
                nome = q.getValue("n"),
            )
        }.getOrNull()
    }
}

object Hex {
    fun codificar(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun decodificar(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    fun chaveValida(hex: String): Boolean =
        hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }
}

object Rede {
    /** Só aceita 10/8, 172.16/12 e 192.168/16: nada de internet pública. */
    fun ipv4Privado(ip: String): Boolean {
        val partes = ip.split('.')
        if (partes.size != 4) return false
        val n = partes.map { p ->
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return false
            p.toInt().also { if (it > 255) return false }
        }
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
    }
}
