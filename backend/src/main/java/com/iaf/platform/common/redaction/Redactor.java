package com.iaf.platform.common.redaction;

import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Masks secrets in a JSON tree before it is kept as evidence or shown through the API. Three rules:
 * a field whose name looks like a secret (password, token, apiKey, credential, authorization...)
 * shows the mask unless its value is a reference ({@code vault:<path>}), which stays visible;
 * any string equal to a secret the platform resolved (or containing one as a word, such as
 * {@code Bearer <token>}) has that secret replaced by the mask; and a word shaped like a
 * well-known token (a JWT, a GitHub, GitLab, Slack or Vault token, an AWS access key id) or a
 * private key block is masked even when the platform never resolved it. The mask is
 * {@value #MASK} unless the caller gives another. Pure: the tree passed in is never changed, the
 * answer is a copy.
 */
public final class Redactor {

    public static final String MASK = "***";
    public static final String REFERENCE_PREFIX = "vault:";

    /** The same words the workflow checks treat as secret-looking ({@code credential.plain}), plus the HTTP ones. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "password|passwd|passphrase|secret|token|apikey|api_key|api-key|accesskey|access_key|privatekey|private_key|credential"
                    + "|authorization|cookie|sessionid|session_id",
            Pattern.CASE_INSENSITIVE);

    /** Words shaped like well-known tokens: masked wherever they turn up, resolved by the platform or not. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*"     // a JWT
                    + "|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"   // GitHub tokens
                    + "|glpat-[A-Za-z0-9_-]{20,}"                                 // GitLab tokens
                    + "|xox[abprs]-[A-Za-z0-9-]{10,}"                             // Slack tokens
                    + "|hv[sbr]\\.[A-Za-z0-9_-]{20,}"                             // Vault tokens
                    + "|AKIA[0-9A-Z]{16}");                                       // AWS access key ids

    /** A PEM private key: the whole string is masked, not just a word of it. */
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

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

    /** A word shaped like a well-known token (see {@link Redactor}). */
    public static boolean tokenShape(String word) {
        return word != null && TOKEN_SHAPE.matcher(word).matches();
    }

    /**
     * {@code node} with secret-looking fields, known secret values and token-shaped words masked
     * with {@value #MASK}; null stays null. A null {@code knownSecret} masks by field name and
     * shape only.
     */
    public static JsonNode redact(JsonNode node, Predicate<String> knownSecret) {
        return redact(node, knownSecret, MASK);
    }

    /** {@link #redact(JsonNode, Predicate)} with another mask, such as the provider file's {@code vault:redacted}. */
    public static JsonNode redact(JsonNode node, Predicate<String> knownSecret, String mask) {
        return node == null ? null : walk(node, knownSecret, mask == null ? MASK : mask);
    }

    /** {@code text} with every known secret and token-shaped word in it masked. */
    public static String redact(String text, Predicate<String> knownSecret) {
        return redact(text, knownSecret, MASK);
    }

    /** {@link #redact(String, Predicate)} with another mask. */
    public static String redact(String text, Predicate<String> knownSecret, String mask) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String m = mask == null ? MASK : mask;
        if ((knownSecret != null && knownSecret.test(text)) || PRIVATE_KEY.matcher(text).find()) {
            return m;
        }
        Matcher words = WORD.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (words.find()) {
            String word = words.group();
            String masked = maskWord(word, knownSecret, m);
            if (masked != null) {
                if (out == null) {
                    out = new StringBuilder(text.length());
                }
                out.append(text, last, words.start()).append(masked);
                last = words.end();
            }
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }

    /** The word with its secret masked, or null when it holds none. {@code user:secret} pairs are looked at per side. */
    private static String maskWord(String word, Predicate<String> knownSecret, String mask) {
        if (secret(word, knownSecret)) {
            return mask;
        }
        int colon = word.indexOf(':');
        if (colon > 0 && colon < word.length() - 1 && !word.startsWith(REFERENCE_PREFIX)) {
            String left = word.substring(0, colon);
            String right = word.substring(colon + 1);
            boolean l = secret(left, knownSecret);
            boolean r = secret(right, knownSecret);
            if (l || r) {
                return (l ? mask : left) + ":" + (r ? mask : right);
            }
        }
        return null;
    }

    private static boolean secret(String word, Predicate<String> knownSecret) {
        return (knownSecret != null && knownSecret.test(word)) || tokenShape(word);
    }

    private static JsonNode walk(JsonNode node, Predicate<String> known, String mask) {
        if (node.isObject()) {
            ObjectNode out = F.objectNode();
            for (Map.Entry<String, JsonNode> f : node.properties()) {
                JsonNode v = f.getValue();
                if (secretKey(f.getKey()) && v != null && !v.isNull() && !v.isContainerNode() && !reference(v)) {
                    out.put(f.getKey(), mask);
                } else if (secretKey(f.getKey()) && v != null && v.isArray()) {
                    ArrayNode masked = F.arrayNode();
                    v.forEach(e -> masked.add(e.isNull() || e.isContainerNode() || reference(e) ? walk(e, known, mask) : F.textNode(mask)));
                    out.set(f.getKey(), masked);
                } else {
                    out.set(f.getKey(), v == null ? null : walk(v, known, mask));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(v -> out.add(walk(v, known, mask)));
            return out;
        }
        if (node.isTextual()) {
            String text = node.asText();
            String masked = redact(text, known, mask);
            return masked.equals(text) ? node : F.textNode(masked);
        }
        return node;
    }
}
