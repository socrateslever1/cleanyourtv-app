package br.espelhotv.celular

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import br.espelhotv.protocolo.Pareamento
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Tela inicial: parear com a TV (QR), configurar o controle e assistir. */
class MainActivity : Activity() {
    private lateinit var conteudo: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Cores.FUNDO)
            addView(conteudo)
        })
        window.statusBarColor = Cores.FUNDO
    }

    override fun onResume() {
        super.onResume()
        ControleTv.aoMudar = { montar() }
        if (Armazenamento.digitalControle(this) != null) ControleTv.conectar(this)
        montar()
    }

    override fun onPause() {
        ControleTv.aoMudar = null
        super.onPause()
    }

    private fun espaco(dp: Int) = android.view.View(this).apply { minimumHeight = dp(dp) }

    private fun montar() {
        conteudo.removeAllViews()
        conteudo.addView(texto("Espelho TV", 32f, Cores.DESTAQUE))
        conteudo.addView(texto("A tela da TV no seu celular, e o celular como controle remoto.", 16f, Cores.SUAVE))
        conteudo.addView(espaco(28))
        val p = Armazenamento.pareamento(this)
        if (p == null) {
            passo("1", "Instale o Espelho TV na TV e abra o app. Ele mostra um QR code.")
            passo("2", "Toque no botão abaixo e aponte a câmera para o QR code.")
            conteudo.addView(espaco(16))
            conteudo.addView(botao("Parear com a TV", primario = true) { lerQr() })
            return
        }
        conteudo.addView(texto("TV: ${p.nome}", 20f))
        conteudo.addView(texto("Endereço na rede: ${p.host}", 14f, Cores.SUAVE))
        conteudo.addView(espaco(20))
        conteudo.addView(botao("▶  Assistir", primario = true) {
            startActivity(Intent(this, AssistirActivity::class.java))
        })
        conteudo.addView(espaco(20))
        when {
            Armazenamento.digitalControle(this) == null -> {
                conteudo.addView(texto("Controle remoto", 20f))
                conteudo.addView(texto("Pareie uma vez para usar o celular como controle: volume, pausar, trocar de canal e ligar a transmissão na TV daqui.", 14f, Cores.SUAVE))
                conteudo.addView(espaco(8))
                conteudo.addView(botao("Configurar controle remoto") { parearControle { montar() } })
            }
            else -> {
                val estado = if (ControleTv.conectado) "conectado" else "desconectado"
                conteudo.addView(texto("Controle remoto ($estado)", 20f))
                ControleTv.ultimoErro?.takeIf { !ControleTv.conectado }?.let {
                    conteudo.addView(texto(it, 13f, Cores.SUAVE))
                }
                conteudo.addView(espaco(8))
                conteudo.addView(botao("Ligar a transmissão na TV") {
                    ControleTv.abrirAppNaTv(this)
                    Toast.makeText(this, "Na TV, aperte OK em \"Começar agora\" (pode usar o OK daqui).", Toast.LENGTH_LONG).show()
                })
                conteudo.addView(painelControle())
            }
        }
        conteudo.addView(espaco(28))
        conteudo.addView(botao("Parear com outra TV") { lerQr() })
        conteudo.addView(espaco(8))
        conteudo.addView(botao("Esquecer esta TV") {
            AlertDialog.Builder(this)
                .setMessage("Esquecer a TV ${p.nome}? Para usar de novo será preciso ler o QR code.")
                .setPositiveButton("Esquecer") { _, _ ->
                    ControleTv.desconectar()
                    Armazenamento.esquecerTudo(this)
                    montar()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        })
    }

    private fun passo(numero: String, t: String) {
        conteudo.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
            addView(texto(numero, 22f, Cores.DESTAQUE).apply { setPadding(0, 0, dp(14), 0) })
            addView(texto(t, 16f))
        })
    }

    private fun lerQr() {
        val opcoes = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opcoes).startScan()
            .addOnSuccessListener { codigo ->
                val p = codigo.rawValue?.let(Pareamento::lerUri)
                if (p == null) {
                    Toast.makeText(this, "Esse QR code não é do Espelho TV.", Toast.LENGTH_LONG).show()
                } else {
                    Armazenamento.salvar(this, p)
                    Toast.makeText(this, "Pareado com ${p.nome}!", Toast.LENGTH_SHORT).show()
                    montar()
                    if (Armazenamento.digitalControle(this) == null) parearControle { montar() }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Não deu para abrir o leitor de QR (precisa do Google Play no celular).", Toast.LENGTH_LONG).show()
            }
    }
}
