package tv.cleanyour.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.cleanyour.core.model.TvId

/** Install is always a deliberate user action; never auto-installs on connect. */
@Composable
internal fun BridgeInstallDialog(
    graph: AppGraph,
    tvId: TvId,
    onInstalled: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = graph.tvs.tvs.value.firstOrNull { it.id == tvId }?.nickname
        ?.takeIf(String::isNotBlank) ?: "TV selecionada"
    var installing by remember(tvId) { mutableStateOf(false) }
    var progress by remember(tvId) { mutableIntStateOf(0) }
    var error by remember(tvId) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!installing) onClose() },
        title = { Text("Instalar CleanYourTV Bridge") },
        text = {
            Text(
                if (installing) "Instalando no dispositivo $target: $progress%."
                else error ?: "TV de destino: $target.\n\n" +
                    "O módulo permite transmitir a tela da TV para este celular. " +
                    "A instalação será feita por ADB somente após sua confirmação. " +
                    "Cada transmissão exige autorização na própria TV, pode ser " +
                    "interrompida e não exibe conteúdo protegido por DRM."
            )
        },
        confirmButton = {
            TextButton(
                enabled = !installing,
                onClick = {
                    if (installing) return@TextButton
                    installing = true
                    error = null
                    scope.launch {
                        try {
                            val result = BridgeInstaller.install(context, graph, tvId) { progress = it }
                            if (result.installed) onInstalled()
                            else error = result.message
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "Não foi possível concluir a instalação na TV."
                        } finally {
                            installing = false
                        }
                    }
                }
            ) { Text(if (error != null) "Tentar novamente" else "Instalar na TV") }
        },
        dismissButton = {
            TextButton(enabled = !installing, onClick = onClose) { Text("Cancelar") }
        }
    )
}
