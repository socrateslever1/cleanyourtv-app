package tv.cleanyour.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.Surface
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.session.AdbTvSession
import tv.cleanyour.core.flows.AccessResult
import tv.cleanyour.core.flows.RemoteKey
import tv.cleanyour.core.flows.mapRemoteTouch
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.Endpoint
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
internal fun TvViewerDialog(graph: AppGraph, tvId: TvId, onInstallBridge: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var frame by remember(tvId) { mutableStateOf<TvFrame?>(null) }
    var status by remember(tvId) { mutableStateOf("Conectando à TV…") }
    var session by remember(tvId) { mutableStateOf<TvSession?>(null) }
    var displaySize by remember { mutableStateOf(IntSize.Zero) }
    var input by remember { mutableStateOf("") }
    var retry by remember(tvId) { mutableStateOf(0) }
    var videoRequested by remember(tvId) { mutableStateOf(true) }
    var videoFailed by remember(tvId) { mutableStateOf(false) }
    var videoPlaying by remember(tvId) { mutableStateOf(false) }
    var videoSurface by remember(tvId) { mutableStateOf<Surface?>(null) }
    var bridgeInstalled by remember(tvId) { mutableStateOf(false) }
    var bridgeSessionKey by remember(tvId) { mutableStateOf<String?>(null) }
    var bridgeStarting by remember(tvId) { mutableStateOf(false) }
    val bridgeHost = remember(tvId) {
        graph.tvs.tvs.value.firstOrNull { it.id == tvId }?.endpoints
            ?.filterIsInstance<Endpoint.PlainTcp>()?.firstOrNull()?.host.orEmpty()
    }
    // TextureView composites beneath Compose overlays correctly. An opaque
    // SurfaceView could cover the PNG fallback with black before first video.
    val videoCallback = remember(tvId) {
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                videoSurface?.release()
                videoSurface = Surface(texture)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                videoSurface?.release()
                videoSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }
    // One pending refresh at most: remote-control bursts must not queue dozens
    // of expensive PNG captures or starve interactive ADB commands.
    val refreshRequests = remember(tvId) { Channel<Unit>(Channel.CONFLATED) }
    val remoteFile = remember(tvId, retry) {
        RemotePath(RemoteDir.TMP, listOf("viewer-" + java.util.UUID.randomUUID().toString() + ".png"))
    }
    val runSpec = remember { ExecSpec(timeoutMs = 7000, idleTimeoutMs = 5000, stdoutCap = 4096,
        stderrCap = 1024, retry = RetryClass.NEVER) }

    // Prefer actual H.264 video on Chromecast/Google TV that permits screenrecord.
    // Each recording ends at 150 s and is restarted; incompatible firmware
    // automatically falls back to the safer PNG preview without data loss.
    LaunchedEffect(tvId, session, videoSurface, videoRequested, videoFailed, bridgeSessionKey) {
        val tv = session as? AdbTvSession ?: return@LaunchedEffect
        val surface = videoSurface?.takeIf { it.isValid } ?: return@LaunchedEffect
        if (!videoRequested || videoFailed || bridgeSessionKey != null) return@LaunchedEffect
        val hold = tv.hold()
        try {
            var failures = 0
            while (isActive && surface.isValid) {
                var displayed = false
                val worked = TvH264Player.play(tv, surface) {
                    displayed = true
                    videoPlaying = true
                }
                videoPlaying = false
                if (worked) {
                    failures = 0
                    delay(250)
                } else {
                    failures++
                    if (!displayed || failures >= 3) {
                        videoFailed = true
                        break
                    }
                    delay(1200)
                }
            }
        } finally {
            videoPlaying = false
            hold.close()
        }
    }

    LaunchedEffect(tvId, session) {
        bridgeInstalled = if (session == null) false
            else runCatching { BridgeInstaller.installed(graph, tvId) }.getOrDefault(false)
    }

    LaunchedEffect(tvId, bridgeSessionKey, videoSurface, session) {
        val secret = bridgeSessionKey ?: return@LaunchedEffect
        val surface = videoSurface?.takeIf { it.isValid } ?: return@LaunchedEffect
        if (session == null || !BridgeClient.privateIpv4(bridgeHost)) return@LaunchedEffect
        status = "Aguardando autorização da captura na TV…"
        try {
            val actualVideo = TvH264Player.playBridge(bridgeHost, secret, surface) {
                videoPlaying = true
                status = "Espelhamento ao vivo iniciado."
            }
            if (!actualVideo) {
                status = "O módulo não transmitiu vídeo. Confira a autorização na TV e a rede local."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status = "Transmissão interrompida. Verifique a autorização na TV."
        } finally {
            videoPlaying = false
            bridgeSessionKey = null
        }
    }

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
            var directCaptureAvailable = connected is AdbTvSession
            while (isActive) {
                // The H.264 decoder owns the screen while streaming.
                // Avoid competing ADB screenshot work and excessive TV CPU use.
                if (videoPlaying) {
                    withTimeoutOrNull(1_200) { refreshRequests.receive() }
                    continue
                }
                val next = try {
                    withContext(Dispatchers.IO) {
                        // Fast path: read binary PNG directly from ADB shell v2.
                        // Avoid mkdir, writing a file, SYNC pull and rm on every
                        // frame; fall back once per session if the TV refuses it.
                        val direct = if (directCaptureAvailable) {
                            runCatching { (connected as AdbTvSession).captureScreenPng()?.let(::decodeTvFrame) }
                                .getOrNull()
                        } else null
                        if (direct != null) direct else {
                            directCaptureAvailable = false
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
                    }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
                if (next != null) {
                    frame = next
                    if (bridgeSessionKey == null) status = "Prévia por capturas periódicas (não é vídeo fluido)."
                    errors = 0
                } else {
                    errors++
                    if (bridgeSessionKey == null) status = if (errors >= 3) "A TV não disponibilizou a imagem. Conteúdo protegido pode aparecer preto." else
                        "Falha na captura; tentando novamente…"
                    if (errors >= 3) break
                }
                // Refresh at least once every 1.2 s, but also immediately after
                // each completed remote action. A conflated signal bounds memory.
                withTimeoutOrNull(1_200) { refreshRequests.receive() }
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
                else refreshRequests.trySend(Unit)
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { status = "Não foi possível enviar o comando. Verifique a conexão." }
        }
    }
    fun press(key: RemoteKey) { submit("input keyevent ${key.androidKeyCode}") }

    Dialog(onDismissRequest = { bridgeSessionKey = null; onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.98f).fillMaxHeight(.94f)) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Visualizar e controlar a TV")
                    OutlinedButton(onClick = { bridgeSessionKey = null; onClose() }) { Text("Fechar") }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .onSizeChanged { displaySize = it }
                    .pointerInput(frame?.originalWidth, frame?.originalHeight, displaySize, videoPlaying) {
                        detectTapGestures { pos ->
                            val img = frame ?: return@detectTapGestures
                            val mapped = mapRemoteTouch(pos.x, pos.y, displaySize.width, displaySize.height,
                                img.originalWidth, img.originalHeight) ?: return@detectTapGestures
                            submit("input tap ${mapped.x} ${mapped.y}")
                        }
                    }, contentAlignment = Alignment.Center) {
                    if ((videoRequested && !videoFailed) || bridgeSessionKey != null) {
                        AndroidView(
                            factory = { context ->
                                TextureView(context).apply { surfaceTextureListener = videoCallback }
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            onRelease = { view ->
                                view.surfaceTextureListener = null
                                videoSurface?.release()
                                videoSurface = null
                            },
                        )
                    }
                    if (!videoPlaying || videoFailed || !videoRequested) {
                        frame?.let { Image(it.bitmap.asImageBitmap(), "Imagem atual da TV", Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit) }
                        if (frame == null) Text("Aguardando imagem da TV", color = androidx.compose.ui.graphics.Color.White)
                    }
                }
                Text(if (videoPlaying) "Vídeo ao vivo pela rede local (H.264, sem áudio)." else status)
                if (session != null) {
                    if (!bridgeInstalled) {
                        Button(onClick = onInstallBridge) { Text("Instalar módulo de espelhamento na TV") }
                    } else {
                        Button(
                            enabled = !bridgeStarting && BridgeClient.privateIpv4(bridgeHost),
                            onClick = {
                                if (bridgeSessionKey != null) {
                                    bridgeSessionKey = null
                                    status = "Espelhamento encerrado no celular."
                                } else {
                                    bridgeStarting = true
                                    scope.launch {
                                        try {
                                            val key = BridgeClient.newKey()
                                            val command = "am start -n tv.cleanyour.bridge/.MirrorActivity --es session_key $key"
                                            val result = withContext(Dispatchers.IO) {
                                                session?.exec(ShellLine(command), runSpec)
                                            }
                                            if (result?.exit == 0 && !result.truncated) {
                                                bridgeSessionKey = key
                                                status = "Autorize o compartilhamento na tela da TV."
                                            } else status = "Não foi possível abrir o módulo. Verifique sua instalação na TV."
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            status = "Falha ao iniciar o módulo. Verifique a conexão da TV."
                                        } finally {
                                            bridgeStarting = false
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (bridgeSessionKey == null) "Espelhar pelo módulo da TV" else "Parar espelhamento")
                        }
                    }
                }
                Text(if (videoRequested && !videoFailed)
                    "Tentando transmissão contínua. Se este Chromecast não permitir, a prévia será mantida."
                    else "Prévia por capturas periódicas; não é vídeo contínuo.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        videoRequested = !videoRequested
                        videoFailed = false
                        videoPlaying = false
                    }, enabled = session != null) {
                        Text(if (videoRequested) "Usar prévia" else "Tentar vídeo ao vivo")
                    }
                    OutlinedButton(onClick = { refreshRequests.trySend(Unit) },
                        enabled = session != null && !videoPlaying) {
                        Text("Atualizar imagem")
                    }
                }
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
