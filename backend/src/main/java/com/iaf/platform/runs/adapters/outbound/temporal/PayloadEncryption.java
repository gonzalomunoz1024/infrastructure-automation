package com.iaf.platform.runs.adapters.outbound.temporal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import io.temporal.api.common.v1.Payload;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.SerializationContext;

/**
 * Encrypts what the platform writes to Temporal's history (AES-256-GCM, {@code platform.temporal.payload-key}):
 * workflow inputs and results, signals, queries, and the inputs and results of the platform's own
 * activities, which may echo a secret a step read. Each payload is sealed whole, its metadata
 * included, under a fresh 96-bit nonce; the sealed payload says {@value #ENCODING}, the cipher
 * and the key's id (the first 8 bytes of its SHA-256, which tells keys apart and reveals
 * nothing), and the key id is the authenticated data, so a payload sealed under another key
 * fails as such.
 *
 * <p>Decoding opens what is sealed and lets everything else through, so histories written before
 * the key was set still replay. A call on a team's worker (an activity on another task queue)
 * is left as it is: its payload is the contract the provider SDKs read, and the worker has no
 * key.</p>
 */
final class PayloadEncryption implements PayloadCodec {

    static final String ENCODING = "binary/encrypted";
    static final String CIPHER = "AES/GCM/NoPadding";
    static final String METADATA_ENCODING = "encoding";
    static final String METADATA_CIPHER = "encryption-cipher";
    static final String METADATA_KEY_ID = "encryption-key-id";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String keyId;
    private final String platformQueue;
    /** This instance encodes for a team's worker: it seals nothing. */
    private final boolean teamCall;

    private PayloadEncryption(SecretKeySpec key, String keyId, String platformQueue, boolean teamCall) {
        this.key = key;
        this.keyId = keyId;
        this.platformQueue = platformQueue;
        this.teamCall = teamCall;
    }

    /**
     * The codec of a base64 AES-256 key; {@code platformQueue} is the interpreter's task queue,
     * whose activities are the platform's own (any other queue is a team's worker).
     */
    static PayloadEncryption of(String base64Key, String platformQueue) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("platform.temporal.payload-key must name a base64 key: " + e.getMessage());
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("platform.temporal.payload-key must name a 32-byte AES-256 key, base64 (it has " + raw.length + " bytes)");
        }
        return new PayloadEncryption(new SecretKeySpec(raw, "AES"), keyId(raw), platformQueue, false);
    }

    private static String keyId(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The id the sealed payloads carry. */
    String keyId() {
        return keyId;
    }

    @Override
    public PayloadCodec withContext(SerializationContext context) {
        boolean team = context instanceof ActivitySerializationContext a && !a.isLocal() && a.getActivityTaskQueue() != null
                && !a.getActivityTaskQueue().isBlank() && !a.getActivityTaskQueue().equals(platformQueue);
        return team == teamCall ? this : new PayloadEncryption(key, keyId, platformQueue, team);
    }

    @Override
    public List<Payload> encode(List<Payload> payloads) {
        return teamCall ? payloads : payloads.stream().map(this::seal).toList();
    }

    @Override
    public List<Payload> decode(List<Payload> payloads) {
        return payloads.stream().map(this::open).toList();
    }

    private Payload seal(Payload payload) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = cipher.doFinal(payload.toByteArray());
            ByteBuffer data = ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed);
            return Payload.newBuilder()
                    .putMetadata(METADATA_ENCODING, ByteString.copyFromUtf8(ENCODING))
                    .putMetadata(METADATA_CIPHER, ByteString.copyFromUtf8(CIPHER))
                    .putMetadata(METADATA_KEY_ID, ByteString.copyFromUtf8(keyId))
                    .setData(ByteString.copyFrom(data.array()))
                    .build();
        } catch (GeneralSecurityException e) {
            throw new PayloadCodecException("could not encrypt a payload: " + e.getMessage(), e);
        }
    }

    private Payload open(Payload payload) {
        ByteString encoding = payload.getMetadataMap().get(METADATA_ENCODING);
        if (encoding == null || !ENCODING.equals(encoding.toStringUtf8())) {
            return payload;
        }
        ByteString id = payload.getMetadataMap().get(METADATA_KEY_ID);
        String sealedWith = id == null ? "" : id.toStringUtf8();
        if (!keyId.equals(sealedWith)) {
            throw new PayloadCodecException("a payload was encrypted with key " + sealedWith + ", not with this instance's key " + keyId
                    + " (platform.temporal.payload-key)");
        }
        byte[] data = payload.getData().toByteArray();
        if (data.length <= NONCE_BYTES) {
            throw new PayloadCodecException("an encrypted payload is too short to open");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES));
            cipher.updateAAD(sealedWith.getBytes(StandardCharsets.US_ASCII));
            return Payload.parseFrom(cipher.doFinal(data, NONCE_BYTES, data.length - NONCE_BYTES));
        } catch (AEADBadTagException e) {
            throw new PayloadCodecException("an encrypted payload does not open: it was changed after it was sealed", e);
        } catch (GeneralSecurityException e) {
            throw new PayloadCodecException("could not decrypt a payload: " + e.getMessage(), e);
        } catch (InvalidProtocolBufferException e) {
            throw new PayloadCodecException("an encrypted payload opened to something that is not a payload", e);
        }
    }
}
