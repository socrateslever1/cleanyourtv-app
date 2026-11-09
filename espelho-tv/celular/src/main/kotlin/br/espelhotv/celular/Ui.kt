package br.espelhotv.celular

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import br.espelhotv.protocolo.ControleRemoto

object Cores {
    val FUNDO = Color.parseColor("#15181E")
    val CARTAO = Color.parseColor("#232833")
    val DESTAQUE = Color.parseColor("#E8A84C")
    val TEXTO = Color.WHITE
    val SUAVE = Color.parseColor("#B8BEC9")
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.botao(texto: String, primario: Boolean = false, acao: () -> Unit) = Button(this).apply {
    text = texto
    isAllCaps = false
    textSize = if (primario) 18f else 16f
    setTextColor(if (primario) Cores.FUNDO else Cores.TEXTO)
    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (primario) Cores.DESTAQUE else Cores.CARTAO)
    }
    setPadding(dp(16), dp(14), dp(16), dp(14))
    setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        acao()
    }
}

fun Context.texto(t: String, tamanho: Float = 16f, cor: Int = Cores.TEXTO) = TextView(this).apply {
    text = t
    textSize = tamanho
    setTextColor(cor)
}

/** Teclado do controle remoto: direcional, OK, voltar, início, volume, mídia. */
fun Activity.painelControle(): View {
    val ctx = this
    fun tecla(rotulo: String, codigo: Int) = botao(rotulo) { ControleTv.tecla(ctx, codigo) }.apply {
        textSize = 18f
    }
    val painel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
    }
    fun linha(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
    }
    painel.addView(linha(
        tecla("⏻", ControleRemoto.LIGA_DESLIGA),
        tecla("Início", ControleRemoto.INICIO),
        tecla("Voltar", ControleRemoto.VOLTAR),
    ))
    val cruz = GridLayout(this).apply { columnCount = 3; rowCount = 3 }
    fun celula(v: View?, linha: Int, coluna: Int) {
        val vv = v ?: View(this)
        cruz.addView(vv, GridLayout.LayoutParams(GridLayout.spec(linha), GridLayout.spec(coluna)).apply {
            width = dp(72); height = dp(60); setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }
    celula(null, 0, 0); celula(tecla("▲", ControleRemoto.CIMA), 0, 1); celula(null, 0, 2)
    celula(tecla("◀", ControleRemoto.ESQUERDA), 1, 0)
    celula(botao("OK", primario = true) { ControleTv.tecla(ctx, ControleRemoto.OK) }, 1, 1)
    celula(tecla("▶", ControleRemoto.DIREITA), 1, 2)
    celula(null, 2, 0); celula(tecla("▼", ControleRemoto.BAIXO), 2, 1); celula(null, 2, 2)
    painel.addView(cruz, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
    painel.addView(linha(
        tecla("Vol −", ControleRemoto.VOLUME_MENOS),
        tecla("Mudo", ControleRemoto.MUDO),
        tecla("Vol +", ControleRemoto.VOLUME_MAIS),
    ))
    painel.addView(linha(
        tecla("Canal −", ControleRemoto.CANAL_MENOS),
        tecla("⏯", ControleRemoto.PLAY_PAUSE),
        tecla("Canal +", ControleRemoto.CANAL_MAIS),
    ))
    return painel
}

/**
 * Pareia o controle remoto: a TV mostra um código de 6 caracteres e o
 * usuário digita no celular. Feito uma vez só.
 */
fun Activity.parearControle(aoTerminar: (Boolean) -> Unit) {
    val p = Armazenamento.pareamento(this) ?: return aoTerminar(false)
    val app = applicationContext
    val aguarde = AlertDialog.Builder(this)
        .setTitle("Controle remoto")
        .setMessage("Falando com a TV…")
        .setCancelable(false)
        .show()
    Thread {
        val sessao = try {
            ControleRemoto.Pareamento(ControleTv.contexto(app), p.host, "Espelho TV (${Build.MODEL})").also { it.iniciar() }
        } catch (e: Exception) {
            runOnUiThread {
                aguarde.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("Não deu para parear o controle")
                    .setMessage("Confira se a TV está ligada e no mesmo Wi-Fi.\n\n(${e.message ?: e.javaClass.simpleName})")
                    .setPositiveButton("OK", null)
                    .show()
                aoTerminar(false)
            }
            return@Thread
        }
        runOnUiThread {
            aguarde.dismiss()
            pedirCodigo(sessao, aoTerminar)
        }
    }.start()
}

private fun Activity.pedirCodigo(sessao: ControleRemoto.Pareamento, aoTerminar: (Boolean) -> Unit) {
    val campo = EditText(this).apply {
        hint = "Ex.: 3F9A2C"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(6), InputFilter.AllCaps())
        textSize = 28f
        gravity = Gravity.CENTER
        letterSpacing = 0.2f
    }
    val caixa = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(campo, LinearLayout.LayoutParams(-1, -2)) }
    AlertDialog.Builder(this)
        .setTitle("Digite o código que apareceu na TV")
        .setView(caixa)
        .setCancelable(false)
        .setNegativeButton("Cancelar") { _, _ -> sessao.close(); aoTerminar(false) }
        .setPositiveButton("Confirmar") { _, _ ->
            val codigo = campo.text.toString()
            Thread {
                val digital = runCatching { sessao.confirmar(codigo) }.getOrNull()
                sessao.close()
                runOnUiThread {
                    if (digital != null) {
                        Armazenamento.salvarControle(this, digital)
                        ControleTv.conectar(this)
                        Toast.makeText(this, "Controle remoto pronto!", Toast.LENGTH_SHORT).show()
                        aoTerminar(true)
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Código não confere")
                            .setMessage("Vamos pedir um código novo para a TV.")
                            .setPositiveButton("Tentar de novo") { _, _ -> parearControle(aoTerminar) }
                            .setNegativeButton("Cancelar") { _, _ -> aoTerminar(false) }
                            .show()
                    }
                }
            }.start()
        }
        .show()
}
