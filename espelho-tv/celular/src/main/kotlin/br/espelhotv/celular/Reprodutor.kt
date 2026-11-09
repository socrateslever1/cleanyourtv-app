package br.espelhotv.celular

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import br.espelhotv.protocolo.CanalSeguro
import br.espelhotv.protocolo.Pacote
import br.espelhotv.protocolo.Pareamento
import br.espelhotv.protocolo.TipoPacote
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Conecta na TV, decodifica o vídeo H.264 na [Surface] e toca o áudio PCM.
 * Reconecta sozinho (inclusive procurando a TV na rede se o IP mudou)
 * até [parar] ser chamado.
 */
class Reprodutor(
    private val ctx: Context,
    private val surface: Surface,
    private val aoEstado: (Estado, String?) -> Unit,
    private val aoTamanho: (Int, Int) -> Unit,
) {
    enum class Estado { PROCURANDO, CONECTANDO, ASSISTINDO, SEM_TRANSMISSAO, CHAVE_RECUSADA }

    @Volatile private var ativo = true
    @Volatile private var socket: Socket? = null
    private val thread = Thread(::laco, "espelhotv-reprodutor")

    fun iniciar() = thread.start()

    fun parar() {
        ativo = false
        runCatching { socket?.close() }
        thread.interrupt()
    }

    private fun laco() {
        while (ativo) {
            val p = Armazenamento.pareamento(ctx) ?: return
            aoEstado(Estado.CONECTANDO, null)
            var s = conectar(p.host, p.porta)
            if (s == null && ativo) {
                aoEstado(Estado.PROCURANDO, null)
                val novo = Descoberta.procurar(ctx, p.id)
                if (novo != null) {
                    Armazenamento.atualizarHost(ctx, novo)
                    s = conectar(novo, p.porta)
                }
            }
            if (s == null) {
                aoEstado(Estado.SEM_TRANSMISSAO, null)
                dormir(2_500)
                continue
            }
            try {
                receber(s, p)
                if (ativo) aoEstado(Estado.SEM_TRANSMISSAO, "A transmissão foi encerrada na TV.")
            } catch (e: java.security.GeneralSecurityException) {
                aoEstado(Estado.CHAVE_RECUSADA, null)
                dormir(4_000)
            } catch (e: Exception) {
                if (ativo) aoEstado(Estado.SEM_TRANSMISSAO, "A conexão caiu. Tentando de novo…")
                dormir(1_000)
            } finally {
                runCatching { s.close() }
            }
        }
    }

    private fun dormir(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun conectar(host: String, porta: Int): Socket? {
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, porta), 2_500)
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20
            socket = s
            s
        } catch (_: IOException) {
            runCatching { s.close() }
            null
        }
    }

    private fun receber(s: Socket, p: Pareamento) {
        s.soTimeout = 8_000
        val canal = try {
            CanalSeguro.conectar(s.getInputStream(), s.getOutputStream(), p.chaveHex)
        } catch (e: java.io.EOFException) {
            // A TV fecha na hora quando a chave não confere (QR trocado na TV).
            throw java.security.GeneralSecurityException("chave recusada")
        }
        var video: Video? = null
        var audio: Audio? = null
        try {
            while (ativo) {
                val pacote = canal.receber() ?: return
                when (pacote.tipo) {
                    TipoPacote.VIDEO_CONFIG -> {
                        video?.fechar()
                        video = Video.criar(pacote, surface, aoTamanho)
                    }
                    TipoPacote.VIDEO -> {
                        val v = video ?: continue
                        if (v.mostrar(pacote)) aoEstado(Estado.ASSISTINDO, null)
                    }
                    TipoPacote.AUDIO_CONFIG -> {
                        audio?.fechar()
                        audio = Audio.criar(pacote)
                    }
                    TipoPacote.AUDIO -> audio?.tocar(pacote.dados)
                    TipoPacote.AVISO -> aoEstado(Estado.ASSISTINDO, pacote.texto())
                }
            }
        } finally {
            video?.fechar()
            audio?.fechar()
        }
    }

    private class Video(private val codec: MediaCodec) {
        private var primeiro = false
        private val info = MediaCodec.BufferInfo()

        /** Retorna true quando o primeiro quadro aparece. */
        fun mostrar(p: Pacote): Boolean {
            val i = codec.dequeueInputBuffer(30_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)
                if (buf != null && buf.capacity() >= p.dados.size) {
                    buf.clear()
                    buf.put(p.dados)
                    codec.queueInputBuffer(i, 0, p.dados.size, p.ptsUs, 0)
                } else {
                    codec.queueInputBuffer(i, 0, 0, p.ptsUs, 0)
                }
            }
            var novo = false
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o < 0) break
                // Mostra na hora: baixa latência vale mais que ritmo perfeito.
                codec.releaseOutputBuffer(o, true)
                if (!primeiro) { primeiro = true; novo = true }
            }
            return novo
        }

        fun fechar() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        companion object {
            fun criar(p: Pacote, surface: Surface, aoTamanho: (Int, Int) -> Unit): Video {
                val b = ByteBuffer.wrap(p.dados)
                val w = b.int
                val h = b.int
                val csd = ByteArray(b.remaining()).also { b.get(it) }
                val nals = separarNal(csd)
                val sps = nals.firstOrNull { tipoNal(it) == 7 }
                val pps = nals.firstOrNull { tipoNal(it) == 8 }
                val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    if (sps != null && pps != null) {
                        setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                        setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                    } else {
                        setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                    }
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(f, surface, null, 0)
                c.start()
                aoTamanho(w, h)
                return Video(c)
            }

            private fun tipoNal(nal: ByteArray): Int {
                val ini = if (nal.size > 3 && nal[2] == 1.toByte()) 3 else 4
                return if (nal.size > ini) nal[ini].toInt() and 0x1f else -1
            }

            /** Divide um bloco Annex-B em unidades NAL, cada uma com seu código inicial. */
            fun separarNal(d: ByteArray): List<ByteArray> {
                val inicios = ArrayList<Int>()
                var i = 0
                while (i + 3 <= d.size) {
                    if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 1.toByte()) {
                        inicios += if (i > 0 && d[i - 1] == 0.toByte()) i - 1 else i
                        i += 3
                    } else i++
                }
                return inicios.mapIndexed { k, ini ->
                    d.copyOfRange(ini, if (k + 1 < inicios.size) inicios[k + 1] else d.size)
                }
            }
        }
    }

    /** Toca o áudio numa thread própria, com fila curta: se atrasar, descarta o mais velho. */
    private class Audio(private val trilha: AudioTrack) {
        private val fila = ArrayBlockingQueue<ByteArray>(12) // ~240 ms
        @Volatile private var vivo = true
        private val thread = Thread({
            while (vivo) {
                val b = try { fila.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
                trilha.write(b, 0, b.size)
            }
        }, "espelhotv-audio").apply { start() }

        fun tocar(b: ByteArray) {
            while (!fila.offer(b)) fila.poll()
        }

        fun fechar() {
            vivo = false
            thread.interrupt()
            runCatching { thread.join(500) }
            runCatching { trilha.stop() }
            trilha.release()
        }

        companion object {
            fun criar(p: Pacote): Audio? {
                val b = ByteBuffer.wrap(p.dados)
                val taxa = b.int
                val canais = b.get().toInt()
                if (taxa !in 8_000..96_000 || canais !in 1..2) return null
                val mascara = if (canais == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minimo = AudioTrack.getMinBufferSize(taxa, mascara, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(taxa)
                            .setChannelMask(mascara)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minimo * 2, taxa / 10 * 2 * canais))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                t.play()
                return Audio(t)
            }
        }
    }
}
