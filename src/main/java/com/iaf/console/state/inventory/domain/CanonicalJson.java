package com.iaf.console.state.inventory.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The one way the trail turns JSON into bytes before hashing it: object members sorted by name
 * (UTF-16 code units, as RFC 8785 sorts them), no whitespace, strings escaped as JSON escapes them,
 * integers in plain digits and other numbers in their shortest decimal form. A stored value read
 * back from any store hashes to the same thing, so a hash can be checked by anyone holding the
 * value, with no access to this service.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** {@code sha256:} and the hex SHA-256 of the canonical form. */
    public static String hash(JsonNode node) {
        return sha256(write(node));
    }

    /** {@code sha256:} and the hex SHA-256 of a text's UTF-8 bytes. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JVM has no SHA-256", e);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                sorted.put(field.getKey(), field.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(field.getKey(), out);
                out.append(':');
                write(field.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            string(node.asText(), out);
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            out.append(value.scale() <= 0 ? value.toBigInteger().toString() : value.toPlainString());
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else {
            string(node.asText(), out);
        }
    }

    private static void string(String value, StringBuilder out) {
        try {
            out.append(Json.MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a string cannot be written as JSON", e);
        }
    }
}
