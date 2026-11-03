package tv.cleanyour.app

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * One-time authenticated, encrypted H264 reception from an optional Android TV
 * Bridge. No untrusted DNS, public hosts, persistent pairing secret or remote
 * control endpoint. The TV must explicitly allow screen capture each session.
 */
internal object BridgeClient {
    const val PACKAGE = "tv.cleanyour.bridge"
    const val PORT = 39761
    private const val MAX_FRAME = 2 * 1024 * 1024
    private val LABEL = "cleanyourtv-bridge-h264-v1".toByteArray(StandardCharsets.US_ASCII)

    fun newKey(): String {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun privateIpv4(ip: String): Boolean {
        val items = ip.split('.')
        if (items.size != 4 || items.any { it.length !in 1..3 || it.any { c -> !c.isDigit() } })
            return false
        val n = items.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168)
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }

    suspend fun receive(ip: String, hex: String, onVideo: suspend (ByteArray) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            require(privateIpv4(ip)) { "Endereço de TV fora da rede local privada" }
            require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' })
            var socket: Socket? = null
            try {
                // The TV needs time to show and receive the user's system-level
                // MediaProjection consent, so retry the local connection only.
                val deadline = System.nanoTime() + 90_000_000_000L
                while (currentCoroutineContext().isActive) {
                    val attempt = Socket()
                    try {
                        attempt.connect(InetSocketAddress(ip, PORT), 2_000)
                        socket = attempt
                        break
                    } catch (e: java.io.IOException) {
                        attempt.close()
                        if (System.nanoTime() >= deadline) return@withContext false
                        delay(650)
                    }
                }
                val peer = socket ?: return@withContext false
                peer.tcpNoDelay = true
                peer.soTimeout = 20_000
                // Cancellation must interrupt blocking reads and release the TV.
                val handler = currentCoroutineContext()[Job]?.invokeOnCompletion { peer.close() }
                try {
                    val input = DataInputStream(peer.getInputStream())
                    val raw = peer.getOutputStream()
                    val secret = ByteArray(32) { i ->
                        val index = i * 2
                        hex.substring(index, index + 2).toInt(16).toByte()
                    }
                    val challenge = ByteArray(16)
                    input.readFully(challenge)
                    raw.write(hmac(secret, challenge))
                    raw.flush()
                    val key = hmac(secret, LABEL + challenge)
                    secret.fill(0)
                    val prefix = challenge.copyOfRange(0, 8)
                    var seq = 0
                    var frames = 0
                    while (currentCoroutineContext().isActive) {
                        val length = try { input.readInt() }
                            catch (_: java.io.EOFException) { break }
                        if (length !in 17..MAX_FRAME) return@withContext false
                        val packet = ByteArray(length)
                        input.readFully(packet)
                        if (seq == Int.MAX_VALUE) return@withContext false
                        val iv = ByteBuffer.allocate(12).put(prefix).putInt(seq++).array()
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                            GCMParameterSpec(128, iv))
                        val chunk = cipher.doFinal(packet)
                        if (chunk.isNotEmpty()) {
                            onVideo(chunk)
                            frames++
                        }
                    }
                    frames > 0
                } finally {
                    handler?.dispose()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                socket?.close()
            }
        }
}
