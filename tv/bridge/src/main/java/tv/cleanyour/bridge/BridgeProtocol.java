package tv.cleanyour.bridge;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One client, one capture session, LAN transport with authentication and
 * per-frame AES-256-GCM. The 256-bit secret is provisioned via the already
 * authorized ADB shell, not sent on the wire.
 *
 * 16-byte server challenge -> 32-byte HMAC-SHA256 response. The first eight
 * challenge bytes are the per-session IV prefix; the last four are a counter.
 * Payloads use AES-256-GCM and are length-prefixed. No permanent credentials.
 */
final class BridgeProtocol {
    static final int PORT = 39761;
    private static final int MAX_PACKET = 2 * 1024 * 1024;
    private static final byte[] LABEL =
        "cleanyourtv-bridge-h264-v1".getBytes(StandardCharsets.US_ASCII);

    private final Socket socket;
    private final DataOutputStream output;
    private final byte[] ivPrefix;
    private final SecretKeySpec aesKey;
    private int sequence;

    private BridgeProtocol(Socket socket, byte[] prefix, byte[] key) throws IOException {
        this.socket = socket;
        this.output = new DataOutputStream(socket.getOutputStream());
        this.ivPrefix = Arrays.copyOf(prefix, 8);
        this.aesKey = new SecretKeySpec(key, "AES");
    }

    static boolean isValidHexKey(String hex) {
        if (hex == null || hex.length() != 64) return false;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static byte[] decodeKey(String hex) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return key;
    }

    private static byte[] mac(byte[] secret, byte[] msg) throws GeneralSecurityException {
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(secret, "HmacSHA256"));
        return h.doFinal(msg);
    }

    static BridgeProtocol authenticate(Socket socket, String secretHex)
        throws IOException, GeneralSecurityException {
        if (!isValidHexKey(secretHex)) throw new GeneralSecurityException("Invalid session key");
        socket.setSoTimeout(15_000);
        byte[] secret = decodeKey(secretHex);
        byte[] challenge = new byte[16];
        new SecureRandom().nextBytes(challenge);
        OutputStream raw = socket.getOutputStream();
        raw.write(challenge);
        raw.flush();
        InputStream in = socket.getInputStream();
        byte[] reply = new byte[32];
        int offset = 0;
        while (offset < reply.length) {
            int n = in.read(reply, offset, reply.length - offset);
            if (n == -1) throw new EOFException("Client disconnected during authentication");
            offset += n;
        }
        byte[] expected = mac(secret, challenge);
        if (!MessageDigest.isEqual(reply, expected))
            throw new GeneralSecurityException("Client authentication rejected");
        byte[] msg = new byte[LABEL.length + challenge.length];
        System.arraycopy(LABEL, 0, msg, 0, LABEL.length);
        System.arraycopy(challenge, 0, msg, LABEL.length, challenge.length);
        byte[] key = mac(secret, msg);
        Arrays.fill(secret, (byte) 0);
        socket.setSoTimeout(0);
        return new BridgeProtocol(socket, challenge, key);
    }

    void send(byte[] videoNal) throws IOException, GeneralSecurityException {
        if (videoNal == null || videoNal.length == 0 || videoNal.length > MAX_PACKET - 16)
            throw new IOException("Invalid video frame size");
        if (sequence == Integer.MAX_VALUE) throw new IOException("Stream packet counter exhausted");
        byte[] nonce = ByteBuffer.allocate(12).put(ivPrefix).putInt(sequence++).array();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(videoNal);
        output.writeInt(encrypted.length);
        output.write(encrypted);
        output.flush();
    }
}
