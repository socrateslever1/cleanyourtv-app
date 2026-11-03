package tv.cleanyour.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional TV-side video source. Requires visible per-session MediaProjection
 * consent; no root, background capture, retained grant or hidden recordings.
 * A single authenticated encrypted client can view one session.
 */
public final class ProjectionService extends Service {
    private static final String CHANNEL = "cleanyourtv_bridge_projection";
    private static final int NOTIFICATION_ID = 83;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ServerSocket listener;
    private volatile Socket client;
    private volatile Thread worker;
    private volatile MediaProjection projection;
    private volatile MediaCodec codec;
    private volatile VirtualDisplay virtualDisplay;
    private volatile Surface inputSurface;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String session = intent.getStringExtra("session_key");
        Intent grant = getProjectionData(intent);
        int resultCode = intent.getIntExtra("projection_result", 0);
        if (!BridgeProtocol.isValidHexKey(session) || grant == null ||
            resultCode != android.app.Activity.RESULT_OK ||
            !active.compareAndSet(false, true)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startVisibleForeground();
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) throw new IllegalStateException("MediaProjection unavailable");
            // Each grant is consumed once. Do not cache/reuse grant after process death.
            MediaProjection p = manager.getMediaProjection(resultCode, grant);
            if (p == null) throw new IllegalStateException("TV declined screen capture");
            projection = p;
            p.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, new Handler(Looper.getMainLooper()));
            final String oneTimeKey = session;
            worker = new Thread(() -> stream(oneTimeKey), "cyt-bridge-stream");
            worker.start();
        } catch (RuntimeException error) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent getProjectionData(Intent src) {
        if (Build.VERSION.SDK_INT >= 33)
            return src.getParcelableExtra("projection_data", Intent.class);
        return src.getParcelableExtra("projection_data");
    }

    private void startVisibleForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                "Espelhamento da TV", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, ProjectionService.class).setAction("STOP");
        PendingIntent stopAction = PendingIntent.getService(this, 0, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = b.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("CleanYourTV: compartilhando tela")
            .setContentText("A imagem está disponível somente para o celular autorizado.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Interromper", stopAction)
            .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void stream(String oneTimeKey) {
        try (ServerSocket server = new ServerSocket()) {
            listener = server;
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress("0.0.0.0", BridgeProtocol.PORT), 1);
            server.setSoTimeout(90_000);
            try (Socket socket = server.accept()) {
                client = socket;
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                BridgeProtocol channel = BridgeProtocol.authenticate(socket, oneTimeKey);
                if (!active.get()) return;
                encode(channel);
            }
        } catch (Exception ignored) {
            // Never log session secrets or frames. The phone displays a
            // localized timeout/compatibility message and can use ADB fallback.
        } finally {
            stopSelf();
        }
    }

    private void encode(BridgeProtocol channel) throws Exception {
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec = encoder;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = encoder.createInputSurface();
        inputSurface = surface;
        encoder.start();
        MediaProjection p = projection;
        if (p == null || !active.get()) return;
        int density = getResources().getDisplayMetrics().densityDpi;
        virtualDisplay = p.createVirtualDisplay("CleanYourTV Bridge",
            1280, 720, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
        if (virtualDisplay == null) throw new IOException("VirtualDisplay unavailable");

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.nanoTime() + 10L * 60 * 1_000_000_000L;
        while (active.get() && System.nanoTime() < deadline) {
            int index = encoder.dequeueOutputBuffer(info, 100_000);
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat out = encoder.getOutputFormat();
                sendCodecParameter(out, "csd-0", channel);
                sendCodecParameter(out, "csd-1", channel);
                continue;
            }
            if (index >= 0) {
                try {
                    if (info.size > 0 && info.size <= 2 * 1024 * 1024) {
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            byte[] data = new byte[info.size];
                            buffer.get(data);
                            channel.send(ensureAnnexB(data));
                        }
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private static void sendCodecParameter(MediaFormat format, String name,
                                           BridgeProtocol channel) throws Exception {
        if (!format.containsKey(name)) return;
        ByteBuffer b = format.getByteBuffer(name);
        if (b == null || !b.hasRemaining() || b.remaining() > 1_000_000) return;
        byte[] bytes = new byte[b.remaining()];
        b.get(bytes);
        channel.send(ensureAnnexB(bytes));
    }

    private static byte[] ensureAnnexB(byte[] data) {
        if (data.length >= 4 && data[0] == 0 && data[1] == 0 &&
            ((data[2] == 1) || (data[2] == 0 && data[3] == 1)))
            return data;
        byte[] framed = Arrays.copyOf(new byte[]{0, 0, 0, 1}, data.length + 4);
        System.arraycopy(data, 0, framed, 4, data.length);
        return framed;
    }

    @Override public void onDestroy() {
        active.set(false);
        Socket s = client;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        ServerSocket server = listener;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
        VirtualDisplay display = virtualDisplay;
        virtualDisplay = null;
        if (display != null) display.release();
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (IllegalStateException ignored) {}
            c.release();
        }
        Surface surface = inputSurface;
        inputSurface = null;
        if (surface != null) surface.release();
        MediaProjection p = projection;
        projection = null;
        if (p != null) p.stop();
        super.onDestroy();
    }
}
