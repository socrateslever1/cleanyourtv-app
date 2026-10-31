package tv.cleanyour.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Local read-only diagnosis. No root, ADB, package enumeration or automated debloat. */
internal object PhoneDiagnostics {
    data class Snapshot(val free: Long, val total: Long, val availableRam: Long, val totalRam: Long) {
        val usedPercent: Int get() = if (total > 0L)
            ((total - free).coerceAtLeast(0L) * 100.0 / total).toInt().coerceIn(0, 100) else 0
        fun guidance(): String = when {
            total <= 0 -> "Armazenamento indisponível: consulte as configurações."
            free.toDouble() / total < 0.1 -> "Pouco espaço livre. Revise downloads e aplicativos nas configurações."
            else -> "Sem alerta crítico de espaço. Evite apagar serviços ou arquivos desconhecidos."
        }
    }

    fun read(context: Context): Snapshot {
        val disk = StatFs(Environment.getDataDirectory().absolutePath)
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        return Snapshot(disk.availableBytes, disk.totalBytes, mem.availMem, mem.totalMem)
    }

    fun gib(value: Long): String = String.format(Locale.forLanguageTag("pt-BR"), "%.2f GiB",
        value.coerceAtLeast(0L) / (1024.0 * 1024 * 1024))
}

@Composable
internal fun PhoneDiagnosticsDialog(context: Context, onClose: () -> Unit) {
    val result = remember { runCatching { PhoneDiagnostics.read(context) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnóstico deste celular") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Modelo: " + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE)
                result.onSuccess { m ->
                    Text("Armazenamento livre: " + PhoneDiagnostics.gib(m.free) + " de " +
                        PhoneDiagnostics.gib(m.total) + " (" + m.usedPercent + "% usado).")
                    Text("RAM disponível: " + PhoneDiagnostics.gib(m.availableRam) + " de " +
                        PhoneDiagnostics.gib(m.totalRam) + ".")
                    Text(m.guidance())
                }.onFailure { Text("Falha na leitura: " + it.javaClass.simpleName) }
                Text("Diagnóstico local. Não desativa serviços, aplicativos ou notificações.")
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            onClose()
        }) { Text("Gerenciar espaço") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Fechar") } },
    )
}
