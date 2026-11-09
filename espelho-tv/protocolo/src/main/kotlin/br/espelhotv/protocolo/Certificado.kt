package br.espelhotv.protocolo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Identidade do celular perante o controle remoto do Google TV: um par RSA
 * 2048 com certificado autoassinado. A TV lembra desse certificado depois do
 * pareamento com código, então ele é guardado num PKCS12 privado do app.
 *
 * O certificado é montado em DER à mão para não depender de BouncyCastle.
 */
object Certificado {
    const val ALIAS = "espelhotv"
    private val SENHA = "espelhotv".toCharArray()

    fun carregarOuCriar(arquivo: File, nomeComum: String): KeyStore {
        if (arquivo.exists()) {
            runCatching {
                return KeyStore.getInstance("PKCS12").apply {
                    arquivo.inputStream().use { load(it, SENHA) }
                }
            }
            arquivo.delete()
        }
        val par = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = autoassinado(par, nomeComum)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, par.private, SENHA, arrayOf(cert))
        }
        val tmp = File(arquivo.parentFile, arquivo.name + ".tmp")
        tmp.outputStream().use { ks.store(it, SENHA) }
        tmp.renameTo(arquivo)
        return ks
    }

    fun senha(): CharArray = SENHA.copyOf()

    fun autoassinado(par: KeyPair, nomeComum: String): X509Certificate {
        val algoritmo = seq(oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00)) // sha256WithRSA, NULL
        val nome = seq(set(seq(oid("2.5.4.3"), utf8(nomeComum))))
        val agora = System.currentTimeMillis()
        val validade = seq(
            tempo(Date(agora - 24L * 3600 * 1000)),
            tempo(Date(agora + 20L * 365 * 24 * 3600 * 1000)),
        )
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xa0, inteiro(BigInteger.valueOf(2))), // v3
            inteiro(serial),
            algoritmo,
            nome,
            validade,
            nome,
            par.public.encoded, // SubjectPublicKeyInfo já em DER
        )
        val assinatura = assinar(par.private, tbs)
        val der = seq(tbs, algoritmo, tlv(0x03, byteArrayOf(0) + assinatura))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun assinar(chave: PrivateKey, dados: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(chave)
            update(dados)
            sign()
        }

    // --- DER ---
    private fun tlv(tag: Int, valor: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = valor.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xff) }
            else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xff); out.write(n and 0xff) }
        }
        out.write(valor)
        return out.toByteArray()
    }

    private fun seq(vararg itens: ByteArray) = tlv(0x30, itens.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg itens: ByteArray) = tlv(0x31, itens.fold(ByteArray(0)) { a, b -> a + b })
    private fun inteiro(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun utf8(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))

    private fun tempo(d: Date): ByteArray {
        val formato = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, formato.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(pontos: String): ByteArray {
        val p = pontos.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val grupos = ArrayList<Int>()
            var x = v
            do { grupos.add((x and 0x7f).toInt()); x = x shr 7 } while (x > 0)
            for (i in grupos.indices.reversed()) out.write(grupos[i] or if (i > 0) 0x80 else 0)
        }
        return tlv(0x06, out.toByteArray())
    }
}
