package tv.cleanyour.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanyour.core.flows.AccessResult
import tv.cleanyour.core.flows.RemoteKey
import tv.cleanyour.core.flows.mapRemoteTouch
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.ExecSpec
import tv.cleanyour.core.session.RetryClass
import tv.cleanyour.core.session.TvSession

/** Bounded screenshot preview, not a low-latency video stream. */
internal data class TvFrame(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

internal fun decodeTvFrame(bytes: ByteArray): TvFrame? {
    if (bytes.size !in 8..(16 * 1024 * 1024)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width !in 1..8192 || height !in 1..8192) return null
    var sample = 1
    while (width / sample > 1280 || height / sample > 1280) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return TvFrame(bitmap, width, height)
}

/** Fixed allowlist prevents shell metacharacters and accidental command injection. */
internal fun validatedRemoteText(value: String): String? {
    if (value.isEmpty() || value.length > 80 || !value.all { it in 'a'..'z' || it in 'A'..'Z' ||
            it in '0'..'9' || it == ' ' || it in "._@,-" }) return null
    return value.replace(" ", "%s")
}

@OptIn(RawShell::class)
@Composable
internal fun TvViewerDialog(graph: AppGraph, tvId: TvId, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var frame by remember(tvId) { mutableStateOf<TvFrame?>(null) }
    var status by remember(tvId) { mutableStateOf("Conectando à TV…") }
    var session by remember(tvId) { mutableStateOf<TvSession?>(null) }
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    var input by remember { mutableStateOf("") }
    var retry by remember(tvId) { mutableStateOf(0) }
    val remoteFile = remember(tvId, retry) {
        RemotePath(RemoteDir.TMP, listOf("viewer-" + java.util.UUID.randomUUID().toString() + ".png"))
    }
    val runSpec = remember { ExecSpec(timeoutMs = 7000, idleTimeoutMs = 5000, stdoutCap = 4096,
        stderrCap = 1024, retry = RetryClass.NEVER) }

    LaunchedEffect(tvId, retry) {
        val access = graph.access.session(tvId)
        val connected = (access as? AccessResult.Ok)?.session
        if (connected == null) {
            status = when (access) {
                AccessResult.Cancelled -> "Conexão cancelada."
                AccessResult.Unknown -> "Dispositivo não cadastrado."
                else -> "Não foi possível conectar à TV. Verifique a depuração pela rede."
            }
            return@LaunchedEffect
        }
        session = connected
        val hold = connected.hold()
        try {
            status = "Atualizando imagem…"
            var errors = 0
            while (isActive) {
                val next = try {
                    withContext(Dispatchers.IO) {
                        val spec = ExecSpec(timeoutMs = 15000, idleTimeoutMs = 8000, stdoutCap = 1024)
                        val remoteName = remoteFile.absolute
                        try {
                            val result = connected.exec(ShellLine("mkdir -p ${RemoteDir.TMP.path} && screencap -p '$remoteName'"), spec)
                            if (result.exit == 0) connected.pull(remoteFile, 16L * 1024 * 1024)?.let(::decodeTvFrame) else null
                        } finally {
                            withContext(NonCancellable) {
                                withTimeoutOrNull(3000) {
                                    runCatching { connected.exec(ShellLine("rm -f '$remoteName'"),
                                        ExecSpec(timeoutMs = 2500, idleTimeoutMs = 2000, stdoutCap = 1024)) }
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
                if (next != null) {
                    frame = next
                    status = "Visualização por capturas sucessivas (não é vídeo fluido)."
                    errors = 0
                } else {
                    errors++
                    status = if (errors >= 3) "A TV não disponibilizou a imagem. Conteúdo protegido pode aparecer preto." else
                        "Falha na captura; tentando novamente…"
                    if (errors >= 3) break
                }
                delay(1200)
            }
        } finally {
            session = null
            hold.close()
        }
    }

    fun submit(command: String) {
        val s = session ?: return
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { s.exec(ShellLine(command), runSpec) }
                if (result.exit != 0) status = "A TV recusou o comando."
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { status = "Não foi possível enviar o comando. Verifique a conexão." }
        }
    }
    fun press(key: RemoteKey) { submit("input keyevent ${key.androidKeyCode}") }

    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.98f).fillMaxHeight(.94f)) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Visualizar e controlar a TV")
                    OutlinedButton(onClick = onClose) { Text("Fechar") }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .onSizeChanged { displaySize = it }
                    .pointerInput(frame?.originalWidth, frame?.originalHeight, displaySize) {
                        detectTapGestures { pos ->
                            val img = frame ?: return@detectTapGestures
                            val mapped = mapRemoteTouch(pos.x, pos.y, displaySize.width, displaySize.height,
                                img.originalWidth, img.originalHeight) ?: return@detectTapGestures
                            submit("input tap ${mapped.x} ${mapped.y}")
                        }
                    }, contentAlignment = Alignment.Center) {
                    frame?.let { Image(it.bitmap.asImageBitmap(), "Imagem atual da TV", Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit) }
                    if (frame == null) Text("Aguardando imagem da TV", color = androidx.compose.ui.graphics.Color.White)
                }
                Text(status)
                Text("Toque na imagem para selecionar itens. A atualização pode demorar alguns segundos.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { press(RemoteKey.HOME) }, enabled = session != null) { Text("Início") }
                    OutlinedButton(onClick = { press(RemoteKey.BACK) }, enabled = session != null) { Text("Voltar") }
                    OutlinedButton(onClick = { press(RemoteKey.SELECT) }, enabled = session != null) { Text("OK") }
                }
                OutlinedButton(onClick = { press(RemoteKey.UP) }, enabled = session != null) { Text("↑") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { press(RemoteKey.LEFT) }, enabled = session != null) { Text("←") }
                    OutlinedButton(onClick = { press(RemoteKey.DOWN) }, enabled = session != null) { Text("↓") }
                    OutlinedButton(onClick = { press(RemoteKey.RIGHT) }, enabled = session != null) { Text("→") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { press(RemoteKey.VOLUME_DOWN) }, enabled = session != null) { Text("Volume −") }
                    OutlinedButton(onClick = { press(RemoteKey.VOLUME_UP) }, enabled = session != null) { Text("Volume +") }
                }
                TextField(value = input, onValueChange = { input = it.take(80) },
                    label = { Text("Digitar na TV (letras, números e pontuação básica)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    val sanitized = validatedRemoteText(input) ?: return@Button
                    submit("input text '$sanitized'")
                    input = ""
                }, enabled = session != null && validatedRemoteText(input) != null) { Text("Enviar texto") }
                if (session == null) {
                    OutlinedButton(onClick = { frame = null; retry++ }) { Text("Tentar novamente") }
                }
            }
        }
    }
}
