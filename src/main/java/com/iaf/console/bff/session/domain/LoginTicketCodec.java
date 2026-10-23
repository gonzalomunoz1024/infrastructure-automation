package com.iaf.console.bff.session.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a {@link LoginTicket} into an opaque cookie value (AES-256-GCM under the server's secret)
 * and opens it again. A ticket that was tampered with, sealed by another secret or expired opens
 * to empty.
 */
public final class LoginTicketCodec {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public LoginTicketCodec(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < 32) {
            throw new IllegalArgumentException("the ticket secret needs at least 32 bytes");
        }
        byte[] first32 = new byte[32];
        System.arraycopy(secret, 0, first32, 0, 32);
        this.key = new SecretKeySpec(first32, "AES");
    }

    public String seal(LoginTicket ticket) {
        String plain = String.join("|", enc(ticket.state()), enc(ticket.codeVerifier()), enc(ticket.nonce()), enc(ticket.returnTo()),
                enc(ticket.sessionId() == null ? "" : ticket.sessionId()), ticket.stepUp() ? "1" : "0", Long.toString(ticket.expiresAt().getEpochSecond()));
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return ENCODER.encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot seal a login ticket", e);
        }
    }

    public Optional<LoginTicket> open(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] in = DECODER.decode(value.trim());
            if (in.length <= IV_BYTES) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            String plain = new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 7) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[6]));
            LoginTicket ticket = new LoginTicket(dec(parts[0]), dec(parts[1]), dec(parts[2]), dec(parts[3]), parts[4].isEmpty() ? null : dec(parts[4]),
                    "1".equals(parts[5]), expiresAt);
            return ticket.expired(now) ? Optional.empty() : Optional.of(ticket);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String enc(String s) {
        return ENCODER.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String s) {
        return new String(DECODER.decode(s), StandardCharsets.UTF_8);
    }
}
