package tv.cleanyour.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.session.RetryClass

/**
 * Safe Wear OS preview. The legacy TV prober and planner must never touch a watch.
 * This operation reads only fixed Android properties on an explicitly authorized
 * ADB-over-TCP connection; it cannot disable, uninstall or install anything.
 *
 * Wireless debugging with pairing codes requires a TLS pairing implementation
 * not present in the app; classic TCP 5555 must first be enabled on the watch by
 * an authorized user's computer, then disabled after the inspection.
 */
internal object WearOsReadOnly {
    fun localIpv4(value: String): Boolean {
        val parts = value.trim().split(".")
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 || (n[0] == 192 && n[1] == 168) ||
            (n[0] == 172 && n[1] in 16..31)
    }

    fun featureOfWatch(features: String): Boolean =
        features.lineSequence().any { it.trim() == "feature:android.hardware.type.watch" }

    @OptIn(RawShell::class)
    suspend fun examine(graph: AppGraph, ip: String): String = withContext(Dispatchers.IO) {
        if (!localIpv4(ip)) return@withContext "Informe um endereço IPv4 privado válido na sua rede."
        when (val found = graph.sessions.open(Endpoint.PlainTcp(ip.trim(), 5555), null) {}) {
            is OpenResult.Failed -> "Não foi possível autenticar o ADB (${found.error}). " +
                "Autorize o celular no relógio. Para relógios com pareamento sem fio por código, " +
                "configure previamente o ADB TCP no computador; o pareamento TLS ainda não é suportado no app."
            is OpenResult.Ok -> {
                val session = found.session
                val spec = ExecSpec(timeoutMs = 10_000L, idleTimeoutMs = 6_000L,
                    stdoutCap = 32 * 1024, retry = RetryClass.IDEMPOTENT_READ)
                try {
                    val features = session.exec(ShellLine("pm list features"), spec)
                    if (features.exit != 0 || !featureOfWatch(features.stdout))
                        return@withContext "O dispositivo conectado não foi reconhecido como relógio Android. Nenhuma alteração efetuada."
                    val model = session.exec(ShellLine("getprop ro.product.model"), spec).stdout.trim().take(100)
                    val os = session.exec(ShellLine("getprop ro.build.version.release"), spec).stdout.trim().take(40)
                    val storage = session.exec(ShellLine("df -k /data"), spec).stdout.trim().take(350)
                    "Wear OS identificado: $model\nBase Android: $os\nArmazenamento (KB):\n$storage\n\n" +
                        "Diagnóstico somente leitura. Nenhum aplicativo ou serviço foi alterado."
                } catch (e: Exception) {
                    "O relógio conectou, mas o diagnóstico falhou: ${e.javaClass.simpleName}. Nenhuma alteração efetuada."
                }
            }
        }
    }
}

@Composable
internal fun WearOsReadOnlyDialog(graph: AppGraph, onClose: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Relógios Wear OS — diagnóstico seguro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Conecte seu relógio ao Wi-Fi. Esta versão utiliza ADB TCP na porta 5555, " +
                    "que deve ser ativada pelo computador após o pareamento ADB. " +
                    "A conexão por código diretamente no celular ainda não está implementada.")
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.take(16) },
                    label = { Text("IP do relógio") },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (busy) Text("Lendo apenas dados do sistema…")
                result?.let { Text(it) }
                Text("Protegidos: sensores, serviços de saúde, Bluetooth, pareamento, chamadas, " +
                    "notificações, SOS, pagamentos, relógio e atualizações. " +
                    "A limpeza de Wear OS permanece bloqueada nesta versão.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && WearOsReadOnly.localIpv4(host),
                onClick = {
                    busy = true
                    scope.launch {
                        result = WearOsReadOnly.examine(graph, host)
                        busy = false
                    }
                }
            ) { Text("Diagnosticar") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onClose) { Text("Fechar") }
        }
    )
}
