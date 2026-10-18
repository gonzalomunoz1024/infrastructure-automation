package com.iaf.platform.playbooks.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for secrets in the text files of an upload before they are kept: a private key block;
 * a key named like a secret ({@code password}, {@code token}, {@code secret}, {@code api_key},
 * {@code access_key}, {@code credentials}, {@code authorization}, ...) with a literal value that
 * is neither a {@code vault:<name>} reference, an expression ({@code {{ ... }}}, {@code ${...}})
 * nor an ansible-vault block; a long high-entropy value after a weaker key ({@code key},
 * {@code auth}, {@code pass}, {@code cert}); and the shapes of AWS, GitHub and Slack tokens
 * anywhere. A finding names the file, the line and what was found, never the value. Pure, so
 * tests judge it without an archive.
 */
public final class SecretScan {

    /** The largest file that is scanned; a bigger one is not a playbook. */
    public static final int MAX_SCANNED = 4 * 1024 * 1024;
    /** A value this long with this much entropy after a weak key counts as a secret. */
    static final int LONG_VALUE = 20;
    static final double HIGH_ENTROPY = 3.5;

    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----");
    /** {@code key: value}, {@code "key": value}, {@code key = value}, {@code key=value}, also as a list item. */
    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*(?:-\\s+)?[\"']?([A-Za-z_][A-Za-z0-9_.\\-]*)[\"']?\\s*[:=]\\s*(.*?)\\s*$");
    private static final Pattern STRONG_KEY = Pattern.compile(
            "(?i)(?:^|[_.\\-])(password|passwd|pwd|secret|token|api[_\\-]?key|access[_\\-]?key|private[_\\-]?key|client[_\\-]?secret|credentials?|authorization)(?:$|[_.\\-])");
    private static final Pattern WEAK_KEY = Pattern.compile("(?i)(?:^|[_.\\-])(key|auth|pass|cert)(?:$|[_.\\-])");
    private static final Pattern AWS_KEY_ID = Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})\\b");
    private static final Pattern SLACK_TOKEN = Pattern.compile("\\bxox[abprs]-[0-9A-Za-z\\-]{10,}\\b");
    /** A value that takes its secret from somewhere else: a template, a shell variable, a vault reference. */
    private static final Pattern EXPRESSION = Pattern.compile("\\{\\{|\\$\\{|^\\$[A-Za-z_][A-Za-z0-9_]*$|^vault:|^!vault");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)^<[^>]+>$|^(null|~|none|changeme|change_me|todo|xxx+|\\*{3,})$");
    /** {@code key: value} pairs inside a flow mapping or sequence on one line. */
    private static final Pattern FLOW_PAIR = Pattern.compile("([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*:\\s*(\"[^\"]*\"|'[^']*'|[^,}\\]\\s]+)");
    /** A key that names where a secret is kept, not the secret ({@code vault_password_file}, {@code token_path}). */
    private static final Pattern FILE_KEY = Pattern.compile("(?i)(file|path|dir|directory|filename)$");
    /** A number, a boolean, a duration or a path: not a secret whatever the key says. */
    private static final Pattern PLAIN_VALUE = Pattern.compile("(?i)^(-?\\d+(\\.\\d+)?|\\d+[smhd]|true|false|yes|no|on|off)$|^(/|~|\\./|\\.\\./)");

    private SecretScan() {
    }

    /** One secret found: where, and what kind; never the value. */
    public record Finding(String file, int line, String what) {

        @Override
        public String toString() {
            return file + ":" + line + " " + what;
        }
    }

    /** Whether the bytes are text worth scanning: no NUL byte and valid UTF-8, within {@link #MAX_SCANNED}. */
    public static boolean isText(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_SCANNED) {
            return false;
        }
        for (int i = 0; i < Math.min(content.length, 8192); i++) {
            if (content[i] == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** The findings in a file; a file that is not text has none. */
    public static List<Finding> scan(String file, byte[] content) {
        return isText(content) ? scan(file, new String(content, StandardCharsets.UTF_8)) : List.of();
    }

    public static List<Finding> scan(String file, String text) {
        List<Finding> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int n = 0;
        for (String line : text.split("\r?\n", -1)) {
            n++;
            if (PRIVATE_KEY.matcher(line).find()) {
                out.add(new Finding(file, n, "a private key block"));
                continue;
            }
            if (AWS_KEY_ID.matcher(line).find()) {
                out.add(new Finding(file, n, "what looks like an AWS access key id"));
                continue;
            }
            if (GITHUB_TOKEN.matcher(line).find()) {
                out.add(new Finding(file, n, "what looks like a GitHub token"));
                continue;
            }
            if (SLACK_TOKEN.matcher(line).find()) {
                out.add(new Finding(file, n, "what looks like a Slack token"));
                continue;
            }
            Matcher m = ASSIGNMENT.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String raw = m.group(2).trim();
            if (raw.startsWith("{") && !raw.startsWith("{{") || raw.startsWith("[")) {
                // a flow mapping or sequence: judge each pair it holds
                Matcher pair = FLOW_PAIR.matcher(raw);
                while (pair.find()) {
                    Finding found = judge(file, n, pair.group(1), pair.group(2));
                    if (found != null) {
                        out.add(found);
                        break;
                    }
                }
                continue;
            }
            Finding found = judge(file, n, m.group(1), raw);
            if (found != null) {
                out.add(found);
            }
        }
        return out;
    }

    private static Finding judge(String file, int line, String key, String raw) {
        String value = literal(raw);
        if (value == null || FILE_KEY.matcher(key).find()) {
            return null;
        }
        if (STRONG_KEY.matcher(key).find()) {
            return new Finding(file, line, key + " has a literal value");
        }
        if (WEAK_KEY.matcher(key).find() && value.length() >= LONG_VALUE && entropy(value) >= HIGH_ENTROPY) {
            return new Finding(file, line, key + " has a long high-entropy value");
        }
        return null;
    }

    /**
     * The literal a value is, or null when it is no secret by construction: empty, a
     * {@code vault:} reference, an expression, an ansible-vault block, an anchor or alias, a
     * placeholder, a number, a boolean, a duration, a path, or a mapping/list that continues on
     * the next lines.
     */
    static String literal(String raw) {
        String v = raw == null ? "" : raw.trim();
        boolean quoted = v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0);
        if (!quoted) {
            int comment = v.indexOf(" #");
            if (v.startsWith("#")) {
                comment = 0;
            }
            if (comment >= 0) {
                v = v.substring(0, comment).trim();
            }
        } else {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.isEmpty() || v.startsWith("&") || v.startsWith("*") || v.startsWith("{") && !v.startsWith("{{") || v.startsWith("[")
                || v.matches("^[|>][-+]?$")) {
            return null;
        }
        if (EXPRESSION.matcher(v).find() || PLACEHOLDER.matcher(v).matches() || PLAIN_VALUE.matcher(v).find()) {
            return null;
        }
        return v;
    }

    /** Shannon entropy in bits per character. */
    static double entropy(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : s.toCharArray()) {
            counts.merge(c, 1, Integer::sum);
        }
        double h = 0;
        for (int count : counts.values()) {
            double p = (double) count / s.length();
            h -= p * Math.log(p) / Math.log(2);
        }
        return h;
    }

    /** The refusal for an upload with findings: the file and line of each, never a value. */
    public static String refusal(String upload, List<Finding> findings) {
        int shown = Math.min(10, findings.size());
        StringBuilder b = new StringBuilder(upload).append(" holds what looks like a secret: ");
        for (int i = 0; i < shown; i++) {
            b.append(i > 0 ? "; " : "").append(findings.get(i));
        }
        if (findings.size() > shown) {
            b.append("; and ").append(findings.size() - shown).append(" more");
        }
        return b.append(". Keep secrets out of playbooks: reference them as vault:<name> or an expression").toString();
    }
}
