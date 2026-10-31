package tv.cleanyour.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import java.util.Locale

/** Bounded, explicit ADB operations. No shell input except a validated package name. */
internal object TvTools {
    private val PACKAGE = Regex("""[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+""")
    private val ACTIVITY = Regex("""(?:realActivity=|cmp=)([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+)/""")
    private val RAM = Regex("""(?m)^MemAvailable:\s+(\d+)\s+kB\b""")
    private val READ = ExecSpec(timeoutMs = 12000, idleTimeoutMs = 6000, stdoutCap = 65536,
        stderrCap = 1024, retry = RetryClass.IDEMPOTENT_READ)

    fun validPackage(pkg: String): Boolean = pkg.length <= 180 && PACKAGE.matches(pkg)

    fun recentFrom(output: String): List<String> =
        ACTIVITY.findAll(output.take(65536)).map { it.groupValues[1] }
            .filter { validPackage(it) && !it.startsWith("com.android.") &&
                !it.startsWith("com.google.android.tv.") }.distinct().take(12).toList()

    fun parseDisk(output: String): Pair<Long, Long>? {
        val line = output.lineSequence().drop(1).lastOrNull { it.isNotBlank() } ?: return null
        val columns = line.trim().split(Regex("""\s+"""))
        if (columns.size < 5) return null
        val total = columns[1].toLongOrNull() ?: return null
        val free = columns[3].toLongOrNull() ?: return null
        return if (total > 0 && free in 0..total) total to free else null
    }

    data class Metrics(val total: Long, val free: Long, val ram: Long?) {
        fun summary(): String {
            fun gib(kib: Long) = String.format(Locale.forLanguageTag("pt-BR"),
                "%.2f", kib / (1024.0 * 1024.0))
            return "Armazenamento livre: " + gib(free) + " de " + gib(total) +
                " GiB. RAM disponível: " + (ram?.let { gib(it) + " GiB" } ?: "indisponível") + "."
        }
    }

    @OptIn(RawShell::class)
    private suspend fun verifyTv(graph: AppGraph, id: TvId): Unit {
        val session = graph.access.live(id) ?: error("TV desconectada. Reconecte para usar estas ferramentas.")
        val f = session.exec(ShellLine("pm list features"), READ)
        if (f.exit != 0 || f.truncated) error("Não foi possível confirmar a plataforma.")
        val features = f.stdout.lineSequence().map { it.trim() }.toSet()
        if ("feature:android.hardware.type.watch" in features ||
            ("feature:android.software.leanback" !in features &&
                "feature:android.hardware.type.television" !in features))
            error("O dispositivo não foi confirmado como Android TV. Operação bloqueada.")
    }

    @OptIn(RawShell::class)
    suspend fun recent(graph: AppGraph, id: TvId): List<String> = withContext(Dispatchers.IO) {
        val s = graph.access.live(id) ?: error("TV desconectada.")
        val hold = s.hold()
        try {
            verifyTv(graph, id)
            val response = s.exec(ShellLine("dumpsys activity recents"), READ)
            if (response.exit != 0 || response.truncated) error("A TV não disponibilizou as tarefas recentes completas.")
            recentFrom(response.stdout)
        } finally { hold.close() }
    }

    @OptIn(RawShell::class)
    suspend fun measure(graph: AppGraph, id: TvId): Metrics = withContext(Dispatchers.IO) {
        val s = graph.access.live(id) ?: error("TV desconectada.")
        val hold = s.hold()
        try {
            verifyTv(graph, id)
            val disk = s.exec(ShellLine("df -k /data"), READ)
            val memory = s.exec(ShellLine("cat /proc/meminfo"), READ)
            if (disk.exit != 0 || memory.exit != 0 || disk.truncated || memory.truncated)
                error("As métricas não estão disponíveis neste firmware.")
            val (total, free) = parseDisk(disk.stdout) ?: error("Formato de armazenamento não reconhecido.")
            val ram = RAM.find(memory.stdout)?.groupValues?.get(1)?.toLongOrNull()
            Metrics(total, free, ram)
        } finally { hold.close() }
    }

    @OptIn(RawShell::class)
    suspend fun open(graph: AppGraph, id: TvId, packageName: String): String = withContext(Dispatchers.IO) {
        require(validPackage(packageName)) { "Identificador do aplicativo inválido." }
        val s = graph.access.live(id) ?: error("TV desconectada.")
        val hold = s.hold()
        try {
            verifyTv(graph, id)
            val r = s.exec(ShellLine("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1"),
                ExecSpec(timeoutMs = 12000, idleTimeoutMs = 6000, stdoutCap = 4096, retry = RetryClass.NEVER))
            if (r.exit != 0 || r.stdout.contains("No activities found", true) ||
                r.stderr.contains("No activities found", true))
                "A TV não conseguiu iniciar esse aplicativo."
            else "Abertura solicitada à TV. A posição do vídeo depende do próprio aplicativo."
        } finally { hold.close() }
    }
}

@Composable
internal fun TvToolsDialog(graph: AppGraph, tvId: TvId, onInstallApk: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(tvId) { context.getSharedPreferences("metrics_" + tvId.value, Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("Conecte a TV e escolha uma consulta. Nada é modificado automaticamente.") }
    var apps by remember(tvId) { mutableStateOf<List<String>>(emptyList()) }
    var metrics by remember(tvId) { mutableStateOf<TvTools.Metrics?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    fun launch(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { result = e.message ?: "Operação não concluída." }
            finally { busy = false }
        }
    }

    if (confirm != null) {
        val pkg = confirm!!
        AlertDialog(onDismissRequest = { if (!busy) confirm = null },
            title = { Text("Abrir na TV?") },
            text = { Text("Aplicativo: " + pkg + ". A tela atual poderá ser substituída. A posição de reprodução não é garantida.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                launch { result = TvTools.open(graph, tvId, pkg); confirm = null }
            }) { Text("Abrir") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirm = null }) { Text("Cancelar") } })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text("Ferramentas da TV") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (busy) "Consultando…" else result)
                TextButton(enabled = !busy, onClick = { launch {
                    apps = TvTools.recent(graph, tvId)
                    result = if (apps.isEmpty()) "A TV não forneceu tarefas recentes." else "Aplicativos nas tarefas recentes:"
                } }) { Text("Ver aplicativos recentes") }
                apps.forEach { pkg ->
                    TextButton(enabled = !busy, onClick = { confirm = pkg }) { Text("Abrir: " + pkg) }
                }
                HorizontalDivider()
                TextButton(enabled = !busy, onClick = { launch {
                    metrics = TvTools.measure(graph, tvId)
                    result = "Medição concluída. A variação de RAM não comprova ganho de desempenho."
                } }) { Text("Medir armazenamento e RAM") }
                metrics?.let { m ->
                    Text(m.summary())
                    val baseline = prefs.getLong("free", -1)
                    if (baseline >= 0)
                        Text("Diferença de espaço livre desde a referência: " + ((m.free - baseline) / 1024) + " MiB.")
                    TextButton(enabled = !busy, onClick = {
                        prefs.edit().putLong("free", m.free).apply()
                        result = "Referência salva neste celular. Meça novamente para comparar."
                    }) { Text("Salvar como referência anterior") }
                }
                HorizontalDivider()
                TextButton(enabled = !busy, onClick = onInstallApk) { Text("Instalar APK na TV") }
                Text("Histórico de tarefas não equivale a histórico de vídeos. Não há retomada universal de reprodução.")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Fechar") } })
}
