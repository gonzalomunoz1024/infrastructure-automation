package com.iaf.console.state.trail.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Signs digests with ES256 (ECDSA on P-256 over SHA-256, the signature as the 64 bytes of r and s,
 * base64url) and checks signatures against public keys given as JWKs. The key is the trail's own,
 * from a private EC JWK; without one the process makes a key of its own, and its public half is
 * kept beside the digests, so they verify after a restart all the same.
 */
public final class DigestSigner {

    private static final String ALGORITHM = "SHA256withECDSAinP1363Format";

    private final String keyId;
    private final PrivateKey privateKey;
    private final ECPublicKey publicKey;

    private DigestSigner(String keyId, PrivateKey privateKey, ECPublicKey publicKey) {
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    /** A fresh P-256 key, for a process that was given none. */
    public static DigestSigner generate(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            return new DigestSigner(keyId, pair.getPrivate(), (ECPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no P-256 key could be made", e);
        }
    }

    /** The key of a private EC JWK ({@code kty} EC, {@code crv} P-256, {@code d}, {@code x}, {@code y}); its {@code kid} names it unless one is given. */
    public static DigestSigner fromJwk(JsonNode jwk, String keyId) {
        if (!"EC".equals(jwk.path("kty").asText()) || !"P-256".equals(jwk.path("crv").asText()) || !jwk.hasNonNull("d")) {
            throw new IllegalArgumentException("the trail's signing key is a private EC JWK on P-256 (kty EC, crv P-256, d, x, y)");
        }
        try {
            ECParameterSpec p256 = p256();
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(new ECPrivateKeySpec(unsigned(jwk.path("d").asText()), p256));
            PublicKey publicKey = factory.generatePublic(new ECPublicKeySpec(new ECPoint(unsigned(jwk.path("x").asText()),
                    unsigned(jwk.path("y").asText())), p256));
            String kid = keyId != null && !keyId.isBlank() ? keyId : jwk.path("kid").asText("trail");
            return new DigestSigner(kid, privateKey, (ECPublicKey) publicKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("the trail's signing key is not a usable P-256 key: " + e.getMessage(), e);
        }
    }

    public String keyId() {
        return keyId;
    }

    public String sign(String text) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the digest could not be signed", e);
        }
    }

    /** The public half as a JWK, for {@code GET /v1/activity/keys} and for checking signatures later. */
    public Map<String, Object> publicJwk() {
        return jwk(keyId, publicKey);
    }

    /** Whether {@code signature} is a valid ES256 signature of {@code text} under the public JWK. */
    public static boolean verify(JsonNode publicJwk, String text, String signature) {
        try {
            ECParameterSpec p256 = p256();
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(unsigned(publicJwk.path("x").asText()),
                    unsigned(publicJwk.path("y").asText())), p256));
            Signature check = Signature.getInstance(ALGORITHM);
            check.initVerify(key);
            check.update(text.getBytes(StandardCharsets.UTF_8));
            return check.verify(Base64.getUrlDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static Map<String, Object> jwk(String kid, ECPublicKey key) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "EC");
        jwk.put("crv", "P-256");
        jwk.put("kid", kid);
        jwk.put("use", "sig");
        jwk.put("alg", "ES256");
        jwk.put("x", coordinate(key.getW().getAffineX()));
        jwk.put("y", coordinate(key.getW().getAffineY()));
        return jwk;
    }

    private static String coordinate(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] fixed = new byte[32];
        if (bytes.length > 32) {
            bytes = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        }
        System.arraycopy(bytes, 0, fixed, 32 - bytes.length, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }

    private static BigInteger unsigned(String base64url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64url));
    }

    private static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }
}
