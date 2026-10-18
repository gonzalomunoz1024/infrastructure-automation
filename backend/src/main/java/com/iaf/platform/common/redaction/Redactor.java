package com.iaf.platform.common.redaction;

import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Masks secrets in a JSON tree before it is kept as evidence or shown through the API. Two rules:
 * a field whose name looks like a secret (password, token, apiKey, credential, authorization...)
 * shows {@value #MASK} unless its value is a reference ({@code vault:<path>}), which stays visible;
 * and any string equal to a secret the platform resolved (or containing one as a word, such as
 * {@code Bearer <token>}) has that secret replaced by {@value #MASK}. Pure: the tree passed in is
 * never changed, the answer is a copy.
 */
public final class Redactor {

    public static final String MASK = "***";
    public static final String REFERENCE_PREFIX = "vault:";

    /** The same words the workflow checks treat as secret-looking ({@code credential.plain}), plus the HTTP ones. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "password|passwd|passphrase|secret|token|apikey|api_key|accesskey|access_key|privatekey|private_key|credential"
                    + "|authorization|cookie|sessionid|session_id",
            Pattern.CASE_INSENSITIVE);

    /** The words of a string: what sits between whitespace, quotes and the usual separators of headers, URLs and JSON. */
    private static final Pattern WORD = Pattern.compile("[^\\s\"'`,;=&?<>()\\[\\]{}]+");

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Redactor() {
    }

    /** A field name that looks like it holds a secret. */
    public static boolean secretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).find();
    }

    /** A {@code vault:} reference: it names a secret without holding it, so it stays visible. */
    public static boolean reference(JsonNode value) {
        return value != null && value.isTextual() && value.asText().trim().startsWith(REFERENCE_PREFIX);
    }

    /**
     * {@code node} with secret-looking fields and known secret values masked; null stays null. A
     * null {@code knownSecret} masks by field name only.
     */
    public static JsonNode redact(JsonNode node, Predicate<String> knownSecret) {
        return node == null ? null : walk(node, knownSecret);
    }

    /** {@code text} with every known secret in it masked. */
    public static String redact(String text, Predicate<String> knownSecret) {
        if (text == null || text.isEmpty() || knownSecret == null) {
            return text;
        }
        if (knownSecret.test(text)) {
            return MASK;
        }
        Matcher m = WORD.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String word = m.group();
            String masked = maskWord(word, knownSecret);
            if (masked != null) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, last, m.start()).append(masked);
                last = m.end();
            }
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }

    /** The word with its secret masked, or null when it holds none. {@code user:secret} pairs are looked at per side. */
    private static String maskWord(String word, Predicate<String> knownSecret) {
        if (knownSecret.test(word)) {
            return MASK;
        }
        int colon = word.indexOf(':');
        if (colon > 0 && colon < word.length() - 1 && !word.startsWith(REFERENCE_PREFIX)) {
            String left = word.substring(0, colon);
            String right = word.substring(colon + 1);
            boolean l = knownSecret.test(left);
            boolean r = knownSecret.test(right);
            if (l || r) {
                return (l ? MASK : left) + ":" + (r ? MASK : right);
            }
        }
        return null;
    }

    private static JsonNode walk(JsonNode node, Predicate<String> known) {
        if (node.isObject()) {
            ObjectNode out = F.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                JsonNode v = f.getValue();
                if (secretKey(f.getKey()) && v != null && !v.isNull() && !v.isContainerNode() && !reference(v)) {
                    out.put(f.getKey(), MASK);
                } else if (secretKey(f.getKey()) && v != null && v.isArray()) {
                    ArrayNode masked = F.arrayNode();
                    v.forEach(e -> masked.add(e.isNull() || e.isContainerNode() || reference(e) ? walk(e, known) : F.textNode(MASK)));
                    out.set(f.getKey(), masked);
                } else {
                    out.set(f.getKey(), v == null ? null : walk(v, known));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(v -> out.add(walk(v, known)));
            return out;
        }
        if (node.isTextual() && known != null) {
            String text = node.asText();
            String masked = redact(text, known);
            return masked.equals(text) ? node : F.textNode(masked);
        }
        return node;
    }
}
