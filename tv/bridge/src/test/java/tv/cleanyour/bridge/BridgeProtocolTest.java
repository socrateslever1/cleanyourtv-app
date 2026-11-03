package tv.cleanyour.bridge;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class BridgeProtocolTest {
    private static byte[] hmac(byte[] key, byte[] value) throws Exception {
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(key, "HmacSHA256"));
        return h.doFinal(value);
    }

    @Test public void validatesExactHexSessionKey() {
        assertTrue(BridgeProtocol.isValidHexKey("a".repeat(64)));
        assertFalse(BridgeProtocol.isValidHexKey("a".repeat(63)));
        assertFalse(BridgeProtocol.isValidHexKey("A".repeat(64)));
        assertFalse(BridgeProtocol.isValidHexKey("../" + "a".repeat(61)));
    }

    @Test public void authenticatedVideoIsEncryptedAndDecryptable() throws Exception {
        String session = "0a".repeat(32);
        byte[] video = new byte[]{0, 0, 0, 1, 0x65, (byte) 0x80, 0x22, 0x33};
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket s = listener.accept()) {
                    BridgeProtocol protocol = BridgeProtocol.authenticate(s, session);
                    protocol.send(video);
                } catch (Throwable error) { failure.set(error); }
            });
            server.start();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                client.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(client.getInputStream());
                OutputStream output = client.getOutputStream();
                byte[] challenge = new byte[16];
                input.readFully(challenge);
                byte[] key = new byte[32];
                Arrays.fill(key, (byte) 0x0a);
                output.write(hmac(key, challenge));
                output.flush();
                byte[] label = "cleanyourtv-bridge-h264-v1".getBytes(StandardCharsets.US_ASCII);
                byte[] msg = ByteBuffer.allocate(label.length + 16)
                    .put(label).put(challenge).array();
                byte[] aesKey = hmac(key, msg);
                int length = input.readInt();
                assertTrue(length >= video.length + 16 && length < 1024);
                byte[] ciphertext = new byte[length];
                input.readFully(ciphertext);
                byte[] iv = ByteBuffer.allocate(12)
                    .put(challenge, 0, 8).putInt(0).array();
                Cipher decoder = Cipher.getInstance("AES/GCM/NoPadding");
                decoder.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(128, iv));
                assertArrayEquals(video, decoder.doFinal(ciphertext));
            }
            server.join(6000);
            assertFalse("Stream server did not terminate", server.isAlive());
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
