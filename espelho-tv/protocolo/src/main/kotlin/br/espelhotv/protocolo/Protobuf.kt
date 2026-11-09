package br.espelhotv.protocolo

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Protobuf mínimo, só o necessário para o protocolo de controle remoto do
 * Google TV (varint e campos de tamanho delimitado). Evita uma dependência
 * inteira e o gerador de código.
 */
class ProtoEscritor {
    private val buf = ByteArrayOutputStream()

    fun varint(campo: Int, valor: Long) = apply {
        escreverVarint(buf, (campo.toLong() shl 3) or 0)
        escreverVarint(buf, valor)
    }

    fun varint(campo: Int, valor: Int) = varint(campo, valor.toLong())

    fun bytes(campo: Int, valor: ByteArray) = apply {
        escreverVarint(buf, (campo.toLong() shl 3) or 2)
        escreverVarint(buf, valor.size.toLong())
        buf.write(valor)
    }

    fun texto(campo: Int, valor: String) = bytes(campo, valor.toByteArray(Charsets.UTF_8))

    fun mensagem(campo: Int, valor: ProtoEscritor) = bytes(campo, valor.paraBytes())

    fun paraBytes(): ByteArray = buf.toByteArray()

    companion object {
        fun escreverVarint(saida: OutputStream, valor: Long) {
            var v = valor
            while (true) {
                if (v and 0x7fL.inv() == 0L) {
                    saida.write(v.toInt())
                    return
                }
                saida.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
        }

        /** Mensagem precedida pelo tamanho em varint (formato "delimited"). */
        fun escreverDelimitado(saida: OutputStream, msg: ByteArray) {
            val tmp = ByteArrayOutputStream()
            escreverVarint(tmp, msg.size.toLong())
            tmp.write(msg)
            saida.write(tmp.toByteArray())
            saida.flush()
        }
    }
}

/** Mensagem lida: campos por número. Varints viram Long, delimitados viram ByteArray. */
class ProtoMensagem(private val campos: Map<Int, List<Any>>) {
    fun tem(campo: Int) = campos.containsKey(campo)
    fun varint(campo: Int): Long? = campos[campo]?.firstOrNull { it is Long } as Long?
    fun bytes(campo: Int): ByteArray? = campos[campo]?.firstOrNull { it is ByteArray } as ByteArray?
    fun texto(campo: Int): String? = bytes(campo)?.toString(Charsets.UTF_8)
    fun mensagem(campo: Int): ProtoMensagem? = bytes(campo)?.let(::ler)

    companion object {
        fun ler(dados: ByteArray): ProtoMensagem {
            val campos = linkedMapOf<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var resultado = 0L
                var deslocamento = 0
                while (true) {
                    if (pos >= dados.size) throw IOException("protobuf truncado")
                    val b = dados[pos++].toInt() and 0xff
                    resultado = resultado or ((b and 0x7f).toLong() shl deslocamento)
                    if (b and 0x80 == 0) return resultado
                    deslocamento += 7
                    if (deslocamento > 63) throw IOException("varint inválido")
                }
            }
            while (pos < dados.size) {
                val chave = varint()
                val campo = (chave ushr 3).toInt()
                when ((chave and 7).toInt()) {
                    0 -> campos.getOrPut(campo) { mutableListOf() }.add(varint())
                    2 -> {
                        val n = varint().toInt()
                        if (n < 0 || pos + n > dados.size) throw IOException("protobuf truncado")
                        campos.getOrPut(campo) { mutableListOf() }.add(dados.copyOfRange(pos, pos + n))
                        pos += n
                    }
                    1 -> pos += 8
                    5 -> pos += 4
                    else -> throw IOException("tipo de campo protobuf não suportado")
                }
            }
            return ProtoMensagem(campos)
        }

        fun lerDelimitado(entrada: InputStream, maximo: Int = 64 * 1024): ProtoMensagem {
            var tamanho = 0L
            var deslocamento = 0
            while (true) {
                val b = entrada.read()
                if (b < 0) throw EOFException("conexão encerrada")
                tamanho = tamanho or ((b and 0x7f).toLong() shl deslocamento)
                if (b and 0x80 == 0) break
                deslocamento += 7
                if (deslocamento > 28) throw IOException("tamanho inválido")
            }
            if (tamanho > maximo) throw IOException("mensagem grande demais")
            val dados = ByteArray(tamanho.toInt())
            var lido = 0
            while (lido < dados.size) {
                val n = entrada.read(dados, lido, dados.size - lido)
                if (n < 0) throw EOFException("conexão encerrada")
                lido += n
            }
            return ler(dados)
        }
    }
}
