package com.iaf.platform.gateway.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.iaf.platform.common.domain.Hashes;

/**
 * The rules of an idempotency key: what a valid key is and the fingerprint of the request it was
 * first used with. The fingerprint covers the method, the path, the query (parameters sorted), the
 * pinned workflow version and the body as canonical JSON (keys sorted, no whitespace), so the same
 * request written differently is still the same request. Keys are scoped to the caller: two
 * callers never see each other's answers. Pure, no Spring.
 */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";
    /** The MCP tools/call argument that carries the key. */
    public static final String ARGUMENT = "idempotencyKey";
    public static final int MAX_LENGTH = 255;

    private static final ObjectMapper CANONICAL = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private IdempotencyKeys() {
    }

    /** The key itself, trimmed; 1 to {@value #MAX_LENGTH} visible ASCII characters, else a 400. */
    public static String validate(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty() || k.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(HEADER + " must be 1 to " + MAX_LENGTH + " visible ASCII characters");
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException(HEADER + " must be 1 to " + MAX_LENGTH + " visible ASCII characters");
            }
        }
        return k;
    }

    /** The fingerprint of an HTTP request. */
    public static String fingerprint(String method, String path, String query, String version, byte[] body) {
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        return Hashes.sha256((method == null ? "" : method.toUpperCase()) + "\n" + nullToEmpty(path) + "\n" + sortedQuery(query) + "\n"
                + nullToEmpty(version) + "\n" + canonicalBody(text));
    }

    /** The fingerprint of an MCP tool call: the tool, the pinned version and the arguments. */
    public static String fingerprint(String tool, String version, JsonNode arguments) {
        return Hashes.sha256("mcp\n" + nullToEmpty(tool) + "\n" + nullToEmpty(version) + "\n" + canonical(arguments));
    }

    /** Sorted keys, no whitespace; null for no value. */
    public static String canonical(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "null";
        }
        try {
            return CANONICAL.writeValueAsString(CANONICAL.convertValue(node, Object.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return node.toString();
        }
    }

    /** A JSON body in canonical form; any other body as it is. A blank body is the empty object, as the gateway reads it. */
    static String canonicalBody(String text) {
        if (text.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = CANONICAL.readTree(text);
            return node == null ? text : canonical(node);
        } catch (JsonProcessingException e) {
            return text;
        }
    }

    static String sortedQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String[] parts = query.split("&");
        Arrays.sort(parts);
        return String.join("&", parts);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
