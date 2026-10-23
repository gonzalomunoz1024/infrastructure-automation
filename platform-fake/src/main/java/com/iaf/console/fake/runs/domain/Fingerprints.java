package com.iaf.console.fake.runs.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The fingerprint of a command for idempotent replays (P-21): the method, the path, the body and
 * the context headers that change a request's meaning, never the assertion's signature.
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String method, String path, JsonNode body, String delegatedUser, String delegatedAccount, String wfrn,
            String consoleRequestId, Map<String, String> labels) {
        StringBuilder text = new StringBuilder();
        text.append(method).append('\n').append(path).append('\n');
        text.append(body == null ? "" : canonical(body)).append('\n');
        text.append(delegatedUser == null ? "" : delegatedUser).append('\n');
        text.append(delegatedAccount == null ? "" : delegatedAccount).append('\n');
        text.append(wfrn == null ? "" : wfrn).append('\n');
        text.append(consoleRequestId == null ? "" : consoleRequestId).append('\n');
        new TreeMap<>(labels == null ? Map.of() : labels).forEach((name, value) -> text.append(name).append('=').append(value).append('\n'));
        return sha256(text.toString());
    }

    /** A node as JSON with object keys sorted, so two equal bodies hash alike whatever their order. */
    public static String canonical(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder out = new StringBuilder("{");
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":").append(canonical(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (JsonNode item : node) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(canonical(item));
            }
            return out.append(']').toString();
        }
        return node.toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
