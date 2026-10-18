package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;

/**
 * The secrets of one step. References ({@code vault:<path>}) are resolved at run time; every
 * value resolved is remembered so whatever the step reports (log lines, error messages) can be
 * masked before it leaves the runner. A value never appears in a message, not even a malformed one.
 */
final class StepSecrets {

    static final Duration LOOKUP = Duration.ofSeconds(30);
    static final String MASK = "****";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** Variables a credential may not set: they would change what runs, not what it may reach. */
    private static final Pattern RESERVED = Pattern.compile("^(PATH|HOME|TMPDIR|LD_.*|DYLD_.*|TF_CLI_ARGS.*|TF_CLI_CONFIG_FILE|TF_PLUGIN_CACHE_DIR|TF_DATA_DIR|ANSIBLE_CONFIG|PYTHONPATH|PYTHONHOME)$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CredentialsPort credentials;
    private final Set<String> values = ConcurrentHashMap.newKeySet();

    StepSecrets(CredentialsPort credentials) {
        this.credentials = credentials;
    }

    static boolean isReference(String s) {
        return s != null && s.startsWith("vault:") && s.length() > "vault:".length();
    }

    /** The value behind {@code reference}; {@code what} names the field in messages. */
    String resolve(String reference, String what) {
        if (!isReference(reference)) {
            throw new IllegalArgumentException(what + " is a vault:<path> reference");
        }
        String value = credentials.resolve(reference).block(LOOKUP);
        if (value == null) {
            throw new ProviderCallException("credential_missing", "Credential " + reference + " is not in the vault", false, 0);
        }
        remember(value);
        return value;
    }

    /**
     * Environment variables from a credential: a JSON object of names to values, or
     * {@code NAME=value} lines (blank lines, {@code #} comments and {@code export } are fine).
     */
    Map<String, String> env(String reference, String what) {
        String raw = resolve(reference, what).trim();
        Map<String, String> out = new LinkedHashMap<>();
        if (raw.startsWith("{")) {
            JsonNode o;
            try {
                o = JSON.readTree(raw);
            } catch (IOException e) {
                throw new IllegalArgumentException("Credential " + reference + " is neither a JSON object nor NAME=value lines");
            }
            Iterator<Map.Entry<String, JsonNode>> it = o.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isValueNode() || e.getValue().isNull()) {
                    throw new IllegalArgumentException("Credential " + reference + ": " + safeName(e.getKey()) + " is not a plain value");
                }
                put(out, e.getKey(), e.getValue().asText(), reference);
            }
        } else {
            for (String line : raw.split("\\R")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                if (l.startsWith("export ")) {
                    l = l.substring(7).trim();
                }
                int eq = l.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("Credential " + reference + " is neither a JSON object nor NAME=value lines");
                }
                String value = l.substring(eq + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                put(out, l.substring(0, eq).trim(), value, reference);
            }
        }
        out.values().forEach(this::remember);
        return out;
    }

    private static void put(Map<String, String> out, String name, String value, String reference) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Credential " + reference + ": '" + safeName(name) + "' is not an environment variable name");
        }
        if (RESERVED.matcher(name).matches()) {
            throw new IllegalArgumentException("Credential " + reference + " may not set " + name);
        }
        out.put(name, value);
    }

    private static String safeName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9_.-]", "?");
        return n.length() > 40 ? n.substring(0, 40) + "…" : n;
    }

    void remember(String value) {
        if (value == null) {
            return;
        }
        String v = value.strip();
        if (v.length() >= 4) {
            values.add(v);
        }
        if (value.contains("\n")) {
            for (String line : value.split("\\R")) {
                String l = line.strip();
                if (l.length() >= 8) {
                    values.add(l);
                }
            }
        }
    }

    /** {@code text} with every remembered value replaced by {@value #MASK}. */
    String mask(String text) {
        if (text == null || values.isEmpty()) {
            return text;
        }
        String out = text;
        List<String> longestFirst = values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String v : longestFirst) {
            if (out.contains(v)) {
                out = out.replace(v, MASK);
            }
        }
        return out;
    }
}
