package br.espelhotv.tv

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detalhe: TextView
    private lateinit var botao: Button
    private lateinit var qr: ImageView
    private lateinit var legendaQr: TextView
    /** Aberto pelo celular (espelhotv://iniciar): depois do OK, volta ao que estava passando. */
    private var voltarDepois = false
    private val ouvinte: () -> Unit = { atualizar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(montarTela())
        Estado.ouvir(ouvinte)
        atualizar()
        tratar(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratar(intent)
    }

    override fun onResume() {
        super.onResume()
        mostrarQr()
        atualizar()
    }

    override fun onDestroy() {
        Estado.parar(ouvinte)
        super.onDestroy()
    }

    private fun tratar(i: Intent?) {
        voltarDepois = i?.data?.scheme == "espelhotv"
        if (Estado.atual == Estado.TRANSMITINDO_SEM_CELULAR || Estado.atual == Estado.TRANSMITINDO_COM_CELULAR) {
            if (voltarDepois) moveTaskToBack(true)
            return
        }
        // Ao abrir, já pede a autorização: um OK e pronto.
        solicitar()
    }

    private fun solicitar() {
        val faltam = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prefs = getSharedPreferences("espelhotv", MODE_PRIVATE)
        if (faltam.isNotEmpty() && !prefs.getBoolean("ja_pediu_permissoes", false)) {
            prefs.edit().putBoolean("ja_pediu_permissoes", true).apply()
            requestPermissions(faltam.toTypedArray(), PEDIDO_PERMISSOES)
        } else {
            pedirCaptura()
        }
    }

    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados)
        if (codigo == PEDIDO_PERMISSOES) pedirCaptura()
    }

    private fun pedirCaptura() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val pedido = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pedido, PEDIDO_CAPTURA)
            status.text = "Aperte OK em \"Começar agora\" na janela que apareceu"
        } catch (_: ActivityNotFoundException) {
            Estado.publicar(Estado.ERRO, "Esta TV não tem a janela de autorização de captura de tela. A transmissão não funciona neste aparelho.")
        }
    }

    @Deprecated("API de Activity simples, sem AndroidX")
    override fun onActivityResult(codigo: Int, resultado: Int, dados: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(codigo, resultado, dados)
        if (codigo != PEDIDO_CAPTURA) return
        if (resultado == RESULT_OK && dados != null) {
            TransmissaoService.iniciar(this, resultado, dados)
            if (voltarDepois) moveTaskToBack(true)
        } else {
            Estado.publicar(Estado.ERRO, "A captura não foi autorizada. Aperte \"Começar transmissão\" para tentar de novo.")
        }
    }

    private fun atualizar() {
        if (!::status.isInitialized) return
        when (Estado.atual) {
            Estado.PARADO -> {
                status.text = "Transmissão parada"
                detalhe.text = "Aperte \"Começar transmissão\" e depois OK na janela do Android."
                botao.text = "Começar transmissão"
            }
            Estado.TRANSMITINDO_SEM_CELULAR -> {
                status.text = "Pronto. Esperando o celular"
                detalhe.text = "Pode voltar a assistir: aperte Início no controle. No celular, toque em Assistir."
                botao.text = "Parar transmissão"
            }
            Estado.TRANSMITINDO_COM_CELULAR -> {
                status.text = "Transmitindo para o celular"
                detalhe.text = "Pode voltar a assistir: aperte Início no controle."
                botao.text = "Parar transmissão"
            }
            Estado.ERRO -> {
                status.text = "Não foi possível transmitir"
                detalhe.text = Estado.detalhe ?: ""
                botao.text = "Começar transmissão"
            }
        }
    }

    private fun mostrarQr() {
        val p = Config.pareamento(this)
        if (p == null) {
            qr.setImageDrawable(null)
            legendaQr.text = "Conecte a TV ao Wi-Fi de casa para mostrar o código."
            return
        }
        qr.setImageBitmap(gerarQr(p.paraUri(), 480))
        legendaQr.text = "No celular, abra o Espelho TV e toque em \"Parear com a TV\".\n${p.nome} · ${p.host}"
    }

    private fun gerarQr(texto: String, lado: Int): Bitmap {
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, lado, lado, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(lado * lado) { if (m[it % lado, it / lado]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, lado, lado, Bitmap.Config.ARGB_8888)
    }

    private fun montarTela(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#15181E"))
            setPadding(dp(48), dp(36), dp(48), dp(36))
            gravity = Gravity.CENTER_VERTICAL
        }
        val esquerda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        esquerda.addView(TextView(this).apply {
            text = "Espelho TV"
            setTextColor(Color.parseColor("#E8A84C"))
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
        })
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 24f; setPadding(0, dp(16), 0, 0) }
        detalhe = TextView(this).apply { setTextColor(Color.parseColor("#B8BEC9")); textSize = 18f; setPadding(0, dp(8), 0, dp(24)) }
        esquerda.addView(status)
        esquerda.addView(detalhe)
        fun botao(texto: String, acao: () -> Unit) = Button(this).apply {
            text = texto
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.parseColor("#15181E"))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#E8A84C"))
            }
            setPadding(dp(28), dp(12), dp(28), dp(12))
            setOnFocusChangeListener { v, foco -> v.alpha = if (foco) 1f else 0.7f; v.scaleX = if (foco) 1.05f else 1f; v.scaleY = v.scaleX }
            alpha = 0.7f
            setOnClickListener { acao() }
        }
        botao = botao("Começar transmissão") {
            if (Estado.atual == Estado.TRANSMITINDO_COM_CELULAR || Estado.atual == Estado.TRANSMITINDO_SEM_CELULAR)
                TransmissaoService.parar(this)
            else solicitar()
        }
        esquerda.addView(botao, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val trocar = botao("Trocar código (desconecta os celulares)") {
            Config.novaChave(this)
            TransmissaoService.parar(this)
            mostrarQr()
        }
        esquerda.addView(trocar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        raiz.addView(esquerda, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        qr = ImageView(this)
        direita.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)))
        legendaQr = TextView(this).apply {
            setTextColor(Color.parseColor("#B8BEC9")); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0)
        }
        direita.addView(legendaQr, LinearLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.WRAP_CONTENT))
        raiz.addView(direita)
        botao.requestFocus()
        return raiz
    }

    companion object {
        private const val PEDIDO_PERMISSOES = 1
        private const val PEDIDO_CAPTURA = 2
    }
}
