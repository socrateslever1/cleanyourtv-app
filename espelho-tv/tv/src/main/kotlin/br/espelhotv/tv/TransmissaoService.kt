package br.espelhotv.tv

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import br.espelhotv.protocolo.CanalSeguro
import br.espelhotv.protocolo.TipoPacote
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Fica ativo enquanto a TV transmite. Uma única autorização de captura
 * (o "OK" do Android) vale até a TV desligar ou o usuário parar: o celular
 * pode entrar e sair quantas vezes quiser sem novo OK.
 *
 * Sem celular conectado nada é codificado (a tela virtual fica sem destino).
 */
class TransmissaoService : Service() {
    private val ativo = AtomicBoolean(false)
    private var projecao: MediaProjection? = null
    private var tela: VirtualDisplay? = null
    private var servidor: ServerSocket? = null
    @Volatile private var sessaoAtual: Sessao? = null
    private var nsd: NsdManager.RegistrationListener? = null
    private var largura = 1280
    private var altura = 720
    private var dpi = 160

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ativo.get()) return START_NOT_STICKY
        val resultado = intent?.getIntExtra(EXTRA_RESULTADO, 0) ?: 0
        val dados = intent?.let { pegarDados(it) }
        if (resultado != Activity.RESULT_OK || dados == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            // Android 14: o serviço de primeiro plano precisa existir antes de usar a autorização.
            mostrarNotificacao()
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(resultado, dados) ?: throw IllegalStateException("captura negada")
            projecao = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            medirTela()
            // Uma única tela virtual por autorização (regra do Android 14). O destino
            // (encoder) é trocado a cada celular que conecta.
            tela = p.createVirtualDisplay(
                "Espelho TV", largura, altura, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null,
            ) ?: throw IllegalStateException("tela virtual indisponível")
            ativo.set(true)
            Estado.publicar(Estado.TRANSMITINDO_SEM_CELULAR)
            Thread(::aceitarCelulares, "espelhotv-servidor").start()
            anunciarNaRede()
        } catch (e: Exception) {
            Estado.publicar(Estado.ERRO, e.message)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun pegarDados(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DADOS, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DADOS)

    @Suppress("DEPRECATION")
    private fun medirTela() {
        val m = DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(m)
        val w = maxOf(m.widthPixels, 1)
        val h = maxOf(m.heightPixels, 1)
        // Até 1280x720 (bom para Wi-Fi e para a tela do celular), mantendo a proporção.
        val escala = minOf(1f, 1280f / maxOf(w, h), 720f / minOf(w, h))
        largura = ((w * escala).roundToInt() / 16) * 16
        altura = ((h * escala).roundToInt() / 16) * 16
        dpi = maxOf((m.densityDpi * escala).roundToInt(), 120)
    }

    private fun mostrarNotificacao() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Transmissão", NotificationManager.IMPORTANCE_LOW))
        val parar = PendingIntent.getService(
            this, 0, Intent(this, TransmissaoService::class.java).setAction(ACAO_PARAR),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val abrir = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Espelho TV está transmitindo")
            .setContentText("Só o celular pareado pode ver a tela.")
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Parar", parar).build())
            .build()
        startForeground(NOTIFICACAO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun anunciarNaRede() {
        val info = NsdServiceInfo().apply {
            serviceName = "Espelho TV ${Config.id(this@TransmissaoService)}"
            serviceType = TIPO_NSD
            port = br.espelhotv.protocolo.Pareamento.PORTA_PADRAO
            setAttribute("id", Config.id(this@TransmissaoService))
        }
        val ouvinte = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, erro: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, erro: Int) {}
        }
        runCatching {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, ouvinte)
            nsd = ouvinte
        }
    }

    private fun aceitarCelulares() {
        try {
            ServerSocket().use { srv ->
                servidor = srv
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(br.espelhotv.protocolo.Pareamento.PORTA_PADRAO), 2)
                while (ativo.get()) {
                    val s = srv.accept()
                    Thread({ atender(s) }, "espelhotv-celular").start()
                }
            }
        } catch (_: IOException) {
            // servidor fechado ao parar
        }
    }

    private fun atender(socket: Socket) {
        socket.use {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 10_000
                val canal = CanalSeguro.aceitar(socket.getInputStream(), socket.getOutputStream(), Config.chave(this))
                socket.soTimeout = 0
                // Um celular por vez: quem chega por último assume.
                val sessao = Sessao(socket, canal)
                synchronized(this) {
                    sessaoAtual?.encerrar()
                    sessaoAtual = sessao
                }
                Estado.publicar(Estado.TRANSMITINDO_COM_CELULAR)
                sessao.executar()
            } catch (_: Exception) {
                // celular desconectou ou não tinha a chave; nada a registrar
            } finally {
                if (ativo.get() && sessaoAtual == null) Estado.publicar(Estado.TRANSMITINDO_SEM_CELULAR)
            }
        }
    }

    /** Um celular conectado: encoder de vídeo + captura de áudio enquanto durar a conexão. */
    private inner class Sessao(private val socket: Socket, private val canal: CanalSeguro.Transmissor) {
        private val viva = AtomicBoolean(true)
        private var encoder: MediaCodec? = null

        fun encerrar() {
            viva.set(false)
            runCatching { socket.close() }
        }

        fun executar() {
            val audio = Thread({ capturarAudio() }, "espelhotv-audio").apply { start() }
            try {
                codificarVideo()
            } finally {
                viva.set(false)
                runCatching { socket.close() }
                audio.join(2_000)
                synchronized(this@TransmissaoService) {
                    if (sessaoAtual === this) {
                        tela?.surface = null
                        sessaoAtual = null
                    }
                }
                encoder?.let { e -> runCatching { e.stop() }; runCatching { e.release() } }
            }
        }

        private fun codificarVideo() {
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = e
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, largura, altura).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                // Tela parada também gera quadros, para o celular nunca ficar sem imagem.
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
            e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val entrada = e.createInputSurface()
            e.start()
            synchronized(this@TransmissaoService) {
                if (sessaoAtual !== this) return
                tela?.surface = entrada
            }
            val info = MediaCodec.BufferInfo()
            try {
                while (viva.get() && ativo.get()) {
                    val i = e.dequeueOutputBuffer(info, 100_000)
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val saida = e.outputFormat
                        val csd = annexB(saida.getByteBuffer("csd-0")) + annexB(saida.getByteBuffer("csd-1"))
                        if (csd.isNotEmpty()) enviarConfig(csd)
                        continue
                    }
                    if (i < 0) continue
                    try {
                        if (info.size > 0) {
                            val buf = e.getOutputBuffer(i) ?: continue
                            buf.position(info.offset).limit(info.offset + info.size)
                            val dados = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) enviarConfig(annexB(dados))
                            else canal.enviar(TipoPacote.VIDEO, info.presentationTimeUs, annexB(dados))
                        }
                    } finally {
                        e.releaseOutputBuffer(i, false)
                    }
                }
            } finally {
                synchronized(this@TransmissaoService) { if (sessaoAtual === this) tela?.surface = null }
                entrada.release()
            }
        }

        private fun enviarConfig(csd: ByteArray) {
            val b = ByteBuffer.allocate(8 + csd.size).putInt(largura).putInt(altura).put(csd).array()
            canal.enviar(TipoPacote.VIDEO_CONFIG, 0, b)
        }

        private fun capturarAudio() {
            val p = projecao ?: return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                aviso("Sem som: a permissão de áudio foi negada na TV.")
                return
            }
            val taxa = 48_000
            val formato = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(taxa)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val bloco = taxa / 50 * 4 // 20 ms, estéreo, 16 bits
            val gravador = try {
                AudioRecord.Builder()
                    .setAudioFormat(formato)
                    .setBufferSizeInBytes(maxOf(bloco * 8, AudioRecord.getMinBufferSize(taxa, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (_: Exception) {
                aviso("Sem som: esta TV não permite capturar o áudio.")
                return
            }
            try {
                if (gravador.state != AudioRecord.STATE_INITIALIZED) {
                    aviso("Sem som: esta TV não permite capturar o áudio.")
                    return
                }
                canal.enviar(TipoPacote.AUDIO_CONFIG, 0, ByteBuffer.allocate(5).putInt(taxa).put(2).array())
                gravador.startRecording()
                val buf = ByteArray(bloco)
                val inicio = System.nanoTime()
                while (viva.get()) {
                    val n = gravador.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) canal.enviar(TipoPacote.AUDIO, (System.nanoTime() - inicio) / 1000, buf, 0, n)
                }
            } catch (_: Exception) {
                // conexão caiu
            } finally {
                runCatching { gravador.stop() }
                gravador.release()
            }
        }

        private fun aviso(texto: String) {
            runCatching { canal.enviar(TipoPacote.AVISO, 0, texto.toByteArray()) }
        }
    }

    override fun onDestroy() {
        ativo.set(false)
        sessaoAtual?.encerrar()
        runCatching { servidor?.close() }
        nsd?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        tela?.release()
        tela = null
        projecao?.stop()
        projecao = null
        Estado.publicar(Estado.PARADO)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULTADO = "resultado"
        const val EXTRA_DADOS = "dados"
        const val ACAO_PARAR = "parar"
        const val TIPO_NSD = "_espelhotv._tcp"
        private const val CANAL = "transmissao"
        private const val NOTIFICACAO = 7

        fun iniciar(ctx: Context, resultado: Int, dados: Intent) {
            ctx.startForegroundService(
                Intent(ctx, TransmissaoService::class.java)
                    .putExtra(EXTRA_RESULTADO, resultado)
                    .putExtra(EXTRA_DADOS, dados),
            )
        }

        fun parar(ctx: Context) {
            ctx.startService(Intent(ctx, TransmissaoService::class.java).setAction(ACAO_PARAR))
        }

        /** Garante código inicial 00 00 00 01 em cada unidade NAL (formato que o celular espera). */
        internal fun annexB(b: ByteBuffer?): ByteArray {
            if (b == null || !b.hasRemaining()) return ByteArray(0)
            val d = ByteArray(b.remaining())
            b.duplicate().get(d)
            return annexB(d)
        }

        internal fun annexB(d: ByteArray): ByteArray =
            if (d.size >= 4 && d[0] == 0.toByte() && d[1] == 0.toByte() &&
                (d[2] == 1.toByte() || (d[2] == 0.toByte() && d[3] == 1.toByte()))
            ) d else byteArrayOf(0, 0, 0, 1) + d
    }
}
