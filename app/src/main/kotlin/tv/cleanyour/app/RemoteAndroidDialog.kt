package tv.cleanyour.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import tv.cleanyour.core.ops.RemoteKind
import tv.cleanyour.core.ops.RemoteVerdict
import tv.cleanyour.core.ops.RemoteOperation
import tv.cleanyour.core.ops.RemoteAndroidPolicy

/**
 * Separate, explicit entry for managing a SECOND device. Does not change the
 * original TV onboarding or the Android phone on which this app is running.
 */
@Composable
internal fun RemoteAndroidDialog(graph: AppGraph, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(graph) { RemoteAndroidController(context.applicationContext, graph) }
    var ip by remember { mutableStateOf("") }
    var inventory by remember { mutableStateOf<RemoteAndroidController.Inventory?>(null) }
    var selected by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var message by remember { mutableStateOf("Conecte outro Android previamente autorizado por ADB TCP. Não é pareamento por código.") }
    var busy by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<RemoteStateBackup.Record?>(null) }
    var chosenApk by remember { mutableStateOf<String?>(null) }

    DisposableEffect(manager) {
        onDispose { graph.workScope.launch { manager.disconnect() } }
    }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Falha ao executar a operação." }
            finally { busy = false }
        }
    }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) run {
            val current = backup ?: error("Crie um backup antes de exportar.")
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { RemoteStateBackup.export(current, it) }
                    ?: error("Não foi possível abrir o destino.")
            }
            message = "Backup exportado. Contém estado dos pacotes; APKs e dados privados não estão incluídos."
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) run {
            val info = inventory ?: error("Conecte o dispositivo antes de importar.")
            val loaded = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { RemoteStateBackup.importZip(it) }
                    ?: error("Não foi possível ler o arquivo.")
            }
            require(loaded.deviceId == info.target.deviceId &&
                loaded.firmwareId == info.target.firmwareId &&
                loaded.userId == info.target.userId) {
                "Backup não pertence a este dispositivo, firmware e usuário."
            }
            backup = withContext(Dispatchers.IO) { RemoteStateBackup.saveImported(context, loaded) }
            message = "Integridade do backup verificada por SHA-256; a origem não é autenticada por esse hash. Confira os pacotes antes de restaurar."
        }
    }

    val apkExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val pkg = chosenApk
        chosenApk = null
        if (uri != null && pkg != null) run {
            message = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { manager.exportApk(pkg, it) }
                    ?: error("Não foi possível abrir o destino do APK.")
            }
        }
    }

    if (selected != null) {
        val (pkg, enable) = selected!!
        AlertDialog(onDismissRequest = { if (!busy) selected = null },
            title = { Text(if (enable) "Ativar no outro Android?" else "Desativar no outro Android?") },
            text = { Text("Pacote: $pkg\nO sistema exige backup, identidade, firmware e confirmação antes de alterar. O Android poderá recusar a operação.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { run {
                    val prior = backup?.states?.firstOrNull { it.packageName == pkg }
                    message = if (prior != null && prior.enabled == enable) manager.restore(pkg)
                        else manager.change(pkg, enable)
                    inventory = manager.refresh()
                    selected = null
                } }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("Cancelar") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Gerenciar outro Android") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (busy) "Executando consulta segura…" else message)
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it.take(15) },
                    label = { Text("IP privado do dispositivo") },
                    placeholder = { Text("192.168.1.50") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !busy && WearOsReadOnly.localIpv4(ip), onClick = { run {
                    inventory = manager.connect(ip)
                    backup = manager.loadBackup()
                    message = inventory!!.explanation
                } }) { Text("Conectar dispositivo") }
                inventory?.let { info ->
                    Text("Dispositivo: " + info.model + " (" + info.target.kind.name + ")")
                    Text("Aplicativos de usuário: " + info.apps.size)
                    Text(info.explanation)
                    if (backup == null) Text("Nenhum backup verificado; alterações bloqueadas.")
                    else Text("Backup SHA-256: " + backup!!.sha256.take(16) + "…")
                    TextButton(enabled = !busy, onClick = { run {
                        backup = manager.backup()
                        message = "Backup durável gravado e verificado antes das alterações."
                    } }) { Text("Criar backup do estado") }
                    TextButton(enabled = !busy && backup != null, onClick = {
                        export.launch("cleanyourtv-android-" + info.target.deviceId + ".zip")
                    }) { Text("Exportar backup ZIP") }
                    TextButton(enabled = !busy, onClick = { importPicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                        Text("Importar backup ZIP")
                    }
                    TextButton(enabled = !busy, onClick = { run {
                        message = manager.reconcile()
                        inventory = manager.refresh()
                    } }) { Text("Conferir operação interrompida") }
                    TextButton(enabled = !busy, onClick = { run {
                        inventory = manager.refresh()
                        backup = manager.loadBackup()
                        message = inventory!!.explanation
                    } }) { Text("Atualizar inventário") }
                    HorizontalDivider()
                    Text("Gerenciamento individual — apenas aplicativos de usuário.")
                    info.apps.forEach { app ->
                        val pkg = app.packageName.value
                        val desired = !app.enabled
                        val found = backup
                        val eligible = info.canWrite && found != null &&
                            RemoteAndroidPolicy.evaluate(info.target, app,
                                if (desired) RemoteOperation.ENABLE else RemoteOperation.DISABLE,
                                found.receipt(), app.enabled, System.currentTimeMillis(),
                                restoring = found.states.any { it.packageName == pkg && it.enabled != app.enabled }) ==
                                    RemoteVerdict.Allowed
                        Text(pkg + if (app.enabled) " — ativo" else " — desativado")
                        TextButton(enabled = !busy && eligible, onClick = { selected = pkg to desired }) {
                            Text(if (app.enabled) "Desativar" else "Ativar")
                        }
                        if (found?.states?.any { it.packageName == pkg && it.enabled != app.enabled } == true) {
                            TextButton(enabled = !busy && eligible, onClick = {
                                selected = pkg to found.states.first { it.packageName == pkg }.enabled
                            }) { Text("Restaurar estado do backup") }
                        }
                        TextButton(enabled = !busy, onClick = {
                            chosenApk = pkg
                            apkExport.launch(pkg + ".apk")
                        }) { Text("Salvar APK acessível") }
                        HorizontalDivider()
                    }
                }
                Text("Segurança: não modifica aplicativos de sistema. Wear OS permanece somente leitura até validação de firmware. APKs acessíveis e sem divisão podem ser salvos separadamente; dados privados não são copiados.")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Fechar") } },
    )
}
