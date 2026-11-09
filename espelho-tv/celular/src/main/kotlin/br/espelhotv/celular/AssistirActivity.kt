package br.espelhotv.celular

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Vídeo da TV em cima, controle remoto embaixo. Deitado, o vídeo ocupa a tela
 * toda e um toque mostra/esconde o controle.
 */
class AssistirActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var mensagem: TextView
    private lateinit var aviso: TextView
    private lateinit var acoes: LinearLayout
    private lateinit var controle: View
    private var reprodutor: Reprodutor? = null
    private var controleVisivel = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video = VideoView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = comecar(h)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, a: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) = encerrar()
        })
        video.setOnClickListener { alternarControle() }
        mensagem = texto("", 18f).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
        aviso = texto("", 13f, Cores.SUAVE).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        acoes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            if (Armazenamento.digitalControle(this@AssistirActivity) != null) {
                addView(botao("Ligar a transmissão na TV", primario = true) { ControleTv.abrirAppNaTv(this@AssistirActivity) })
            }
        }
        controle = ScrollView(this).apply {
            setBackgroundColor(Color.argb(225, 21, 24, 30))
            addView(
                if (Armazenamento.digitalControle(this@AssistirActivity) != null) painelControle()
                else texto("Configure o controle remoto na tela inicial para controlar a TV daqui.", 14f, Cores.SUAVE)
                    .apply { setPadding(dp(16), dp(16), dp(16), dp(16)) },
            )
        }
        montarLayout()
        if (Armazenamento.digitalControle(this) != null) ControleTv.conectar(this)
    }

    override fun onConfigurationChanged(novo: Configuration) {
        super.onConfigurationChanged(novo)
        montarLayout()
    }

    private val deitado get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun montarLayout() {
        listOf(video, mensagem, aviso, acoes, controle).forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (deitado) {
            controleVisivel = false
            raiz.addView(video, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            raiz.addView(controle, FrameLayout.LayoutParams(dp(300), -1, Gravity.END))
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            controleVisivel = true
            val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val area = FrameLayout(this)
            area.addView(video, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            coluna.addView(area, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 9) / 16))
            coluna.addView(controle, LinearLayout.LayoutParams(-1, 0, 1f))
            raiz.addView(coluna)
            window.decorView.systemUiVisibility = 0
        }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(mensagem)
            addView(acoes)
        }, FrameLayout.LayoutParams(-1, if (deitado) -1 else (resources.displayMetrics.widthPixels * 9) / 16, Gravity.TOP))
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        controle.visibility = if (controleVisivel) View.VISIBLE else View.GONE
        setContentView(raiz)
    }

    private fun alternarControle() {
        if (!deitado) return
        controleVisivel = !controleVisivel
        controle.visibility = if (controleVisivel) View.VISIBLE else View.GONE
    }

    private fun comecar(h: SurfaceHolder) {
        if (reprodutor != null) return
        mostrar("Conectando à TV…", false)
        reprodutor = Reprodutor(applicationContext, h.surface,
            aoEstado = { estado, texto -> runOnUiThread { aplicar(estado, texto) } },
            aoTamanho = { w, a -> runOnUiThread { video.proporcao(w, a) } },
        ).also { it.iniciar() }
    }

    private fun encerrar() {
        reprodutor?.parar()
        reprodutor = null
    }

    override fun onDestroy() {
        encerrar()
        super.onDestroy()
    }

    private fun aplicar(estado: Reprodutor.Estado, texto: String?) {
        when (estado) {
            Reprodutor.Estado.CONECTANDO -> mostrar("Conectando à TV…", false)
            Reprodutor.Estado.PROCURANDO -> mostrar("Procurando a TV na rede…", false)
            Reprodutor.Estado.SEM_TRANSMISSAO -> mostrar(
                (texto?.let { "$it\n\n" } ?: "") +
                    "A TV não está transmitindo.\nNa TV, abra o Espelho TV e aperte OK em \"Começar agora\".",
                true,
            )
            Reprodutor.Estado.CHAVE_RECUSADA -> mostrar(
                "A TV não reconheceu este celular (o código dela foi trocado).\nVolte e toque em \"Parear com outra TV\" para ler o QR de novo.",
                false,
            )
            Reprodutor.Estado.ASSISTINDO -> {
                mensagem.visibility = View.GONE
                acoes.visibility = View.GONE
                if (texto != null) {
                    aviso.text = texto
                    aviso.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun mostrar(t: String, comAcoes: Boolean) {
        mensagem.text = t
        mensagem.visibility = View.VISIBLE
        acoes.visibility = if (comAcoes) View.VISIBLE else View.GONE
    }
}

/** SurfaceView que mantém a proporção do vídeo da TV dentro do espaço disponível. */
class VideoView(ctx: Context) : SurfaceView(ctx) {
    private var w = 16
    private var h = 9

    fun proporcao(largura: Int, altura: Int) {
        if (largura <= 0 || altura <= 0) return
        w = largura; h = altura
        requestLayout()
    }

    override fun onMeasure(larguraSpec: Int, alturaSpec: Int) {
        val maxW = MeasureSpec.getSize(larguraSpec)
        val maxH = MeasureSpec.getSize(alturaSpec)
        var lw = maxW
        var lh = maxW * h / w
        if (maxH in 1 until lh) {
            lh = maxH
            lw = maxH * w / h
        }
        setMeasuredDimension(lw, lh)
    }
}
