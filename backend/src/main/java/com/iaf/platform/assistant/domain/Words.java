package com.iaf.platform.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small vocabulary the demo agent reads a description with: tokens, stems, the verbs it
 * knows, environments and channels. Product names are never here; they come from the registry.
 */
public final class Words {

    /** The verbs a draft can be about, in canonical form. */
    public static final List<String> VERBS = List.of("create", "delete", "restart", "upgrade", "patch", "resize", "backup", "rotate", "scale", "deploy");

    /** Infrastructure nouns a description may use when the registry has no better word. */
    public static final Set<String> COMMON_NOUNS = Set.of("server", "cluster", "node", "host", "instance", "container", "pod", "service",
            "app", "application", "namespace", "bucket", "disk", "volume", "network", "subnet", "firewall", "certificate", "cert", "secret",
            "key", "user", "account", "queue", "topic", "function", "job", "pipeline", "website", "site", "database", "db", "cache",
            "loadbalancer", "lb", "vpn", "dns", "record", "storage", "snapshot", "backup", "image", "template", "machine", "vm",
            "environment", "tenant", "project", "repository", "repo", "domain", "zone", "schema", "table", "index", "replica", "shard");

    /** Words that carry no meaning of their own. */
    public static final Set<String> STOP = Set.of("a", "an", "the", "to", "of", "for", "with", "in", "on", "and", "or", "then", "after",
            "before", "when", "that", "it", "is", "are", "be", "by", "from", "as", "at", "this", "we", "i", "my", "our", "please",
            "should", "must", "its", "into", "via", "using", "use", "do", "does", "if", "so", "up", "out", "one", "all", "any", "some",
            "which", "what", "who", "there", "their", "they", "them", "can", "could", "would", "will", "need", "needs", "want", "wants",
            "like", "also", "just", "only", "each", "every", "per", "me", "us", "you", "your", "has", "have", "was", "were", "been",
            "being", "workflow", "existing", "published", "step", "steps");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("provision", "create"), Map.entry("build", "create"), Map.entry("make", "create"), Map.entry("new", "create"),
            Map.entry("add", "create"), Map.entry("spin", "create"), Map.entry("launch", "create"), Map.entry("request", "create"),
            Map.entry("setup", "create"),
            Map.entry("remove", "delete"), Map.entry("destroy", "delete"), Map.entry("decommission", "delete"), Map.entry("teardown", "delete"),
            Map.entry("retire", "delete"), Map.entry("terminate", "delete"), Map.entry("drop", "delete"),
            Map.entry("reboot", "restart"), Map.entry("bounce", "restart"), Map.entry("recycle", "restart"),
            Map.entry("update", "upgrade"), Map.entry("bump", "upgrade"), Map.entry("migrate", "upgrade"),
            Map.entry("fix", "patch"), Map.entry("hotfix", "patch"),
            Map.entry("grow", "resize"), Map.entry("shrink", "resize"), Map.entry("expand", "resize"), Map.entry("extend", "resize"),
            Map.entry("snapshot", "backup"),
            Map.entry("renew", "rotate"),
            Map.entry("autoscale", "scale"),
            Map.entry("release", "deploy"), Map.entry("ship", "deploy"), Map.entry("publish", "deploy"), Map.entry("rollout", "deploy"));

    private static final Pattern CHANNEL = Pattern.compile("#[A-Za-z0-9_-]+");
    private static final Pattern VERSION = Pattern.compile("^v?\\d+(\\.\\d+)+$");
    private static final Pattern DURATION = Pattern.compile("^(\\d+)\\s*(ms|s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)$");

    private Words() {
    }

    /** Lowercase tokens; dots inside a token survive (8.0, vm.restart), punctuation does not. */
    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.toLowerCase().replaceAll("[^a-z0-9.#_-]+", " ").split("\\s+")) {
            String t = raw.replaceAll("^[.\\-#_]+|[.\\-_]+$", "");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Tokens without the stop words. */
    public static List<String> content(List<String> tokens) {
        return tokens.stream().filter(t -> !STOP.contains(t)).toList();
    }

    /** A crude stem so that restart, restarting and restarted compare equal. */
    public static String stem(String w) {
        if (w == null) {
            return "";
        }
        String s = w.toLowerCase();
        if (s.endsWith("ies") && s.length() > 4) {
            s = s.substring(0, s.length() - 3) + "y";
        } else if (s.endsWith("ing") && s.length() > 5) {
            s = s.substring(0, s.length() - 3);
            if (s.length() > 3 && s.charAt(s.length() - 1) == s.charAt(s.length() - 2)) {
                s = s.substring(0, s.length() - 1);
            }
        } else if (s.endsWith("ment") && s.length() > 6) {
            s = s.substring(0, s.length() - 4);
        } else if (s.endsWith("tion") && s.length() > 6) {
            s = s.substring(0, s.length() - 3);
        } else if (s.endsWith("ed") && s.length() > 4) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("es") && s.length() > 4) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("s") && s.length() > 3 && !s.endsWith("ss")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("e") && s.length() > 3) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static boolean same(String a, String b) {
        return a != null && b != null && (a.equalsIgnoreCase(b) || stem(a).equals(stem(b)));
    }

    public static boolean contains(List<String> tokens, String word) {
        return tokens.stream().anyMatch(t -> same(t, word));
    }

    public static boolean containsAny(List<String> tokens, String... words) {
        for (String w : words) {
            if (contains(tokens, w)) {
                return true;
            }
        }
        return false;
    }

    /** The canonical verb a token stands for, if any. */
    public static Optional<String> verb(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String t = token.toLowerCase();
        for (String v : VERBS) {
            if (same(t, v)) {
                return Optional.of(v);
            }
        }
        for (Map.Entry<String, String> e : SYNONYMS.entrySet()) {
            if (same(t, e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Words that mean the same as a verb, including the verb: used to match action ids. */
    public static List<String> forms(String verb) {
        List<String> out = new ArrayList<>();
        out.add(verb);
        SYNONYMS.forEach((k, v) -> {
            if (v.equals(verb)) {
                out.add(k);
            }
        });
        return out;
    }

    /** The main verb of a description: the earliest verb, with -ing forms and noun-like verbs ranked later. */
    public static Optional<String> mainVerb(List<String> tokens) {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            Optional<String> v = verb(t);
            if (v.isEmpty()) {
                continue;
            }
            int score = i;
            if (t.endsWith("ing")) {
                score += 20;
            }
            if (("backup".equals(v.get()) || "snapshot".equals(t)) && i > 0) {
                score += 10;
            }
            if (score < bestScore) {
                bestScore = score;
                best = v.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /** dev, test or prod when the description names an environment. */
    public static Optional<String> environment(List<String> tokens) {
        for (String t : tokens) {
            switch (t) {
                case "prod", "production", "live" -> {
                    return Optional.of("prod");
                }
                case "test", "testing", "staging", "stage", "qa", "uat", "preprod" -> {
                    return Optional.of("test");
                }
                case "dev", "development", "sandbox" -> {
                    return Optional.of("dev");
                }
                default -> {
                }
            }
        }
        return Optional.empty();
    }

    /** {@code #channel} mentions, in order. */
    public static List<String> channels(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = CHANNEL.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** A version-looking token (8.0, 3.1.2, v2.0). */
    public static Optional<String> version(List<String> tokens) {
        return tokens.stream().filter(t -> VERSION.matcher(t).matches()).map(t -> t.startsWith("v") ? t.substring(1) : t).findFirst();
    }

    /** A number followed by a unit ("15 minutes", "30m") as a platform duration ("15m"); empty when it is not one. */
    public static Optional<String> duration(String number, String unit) {
        if (number == null || unit == null) {
            return Optional.empty();
        }
        Matcher m = DURATION.matcher(number.trim() + " " + unit.trim().toLowerCase());
        if (!m.matches()) {
            return Optional.empty();
        }
        String u = m.group(2);
        String suffix = switch (u.charAt(0)) {
            case 'h' -> "h";
            case 'd' -> "d";
            case 's' -> "s";
            case 'm' -> u.startsWith("ms") ? "ms" : "m";
            default -> "m";
        };
        return Optional.of(m.group(1) + suffix);
    }

    public static Optional<String> duration(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile("^(\\d+)(ms|s|m|h|d)$").matcher(token.toLowerCase());
        return m.matches() ? Optional.of(token.toLowerCase()) : Optional.empty();
    }

    public static boolean isNumber(String token) {
        return token != null && token.matches("^\\d+$");
    }

    public static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** How a noun reads in a title: short ones are acronyms (vm -> VM, db -> DB). */
    public static String nounTitle(String noun) {
        if (noun == null || noun.isEmpty()) {
            return "";
        }
        if (noun.length() <= 3 && (ACRONYMS.contains(noun.toLowerCase()) || !noun.matches(".*[aeiouy].*"))) {
            return noun.toUpperCase();
        }
        return noun;
    }

    /** Short nouns written in capitals: a word with no vowel, or one of these. */
    private static final java.util.Set<String> ACRONYMS = java.util.Set.of("api", "ip", "os", "db", "id", "ui", "io", "iam", "acl", "ami", "ecs", "eks", "aks", "gke");

    /** camelCase a dotted or dashed id: vm.wait-for -> vmWaitFor. */
    public static String camel(String text) {
        return com.iaf.platform.common.domain.Ids.camel(text == null ? "" : text.replace('.', ' ').replace('_', ' '));
    }

    /** Whether a phrase (stop words removed) appears in order and contiguously inside the tokens. */
    public static boolean phrase(List<String> tokens, List<String> phrase) {
        List<String> hay = content(tokens);
        List<String> needle = content(phrase);
        if (needle.isEmpty() || needle.size() > hay.size()) {
            return false;
        }
        for (int i = 0; i + needle.size() <= hay.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < needle.size(); j++) {
                if (!same(hay.get(i + j), needle.get(j))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** Truncate to a length, on a word boundary when possible. */
    public static String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(' ', max - 1);
        return s.substring(0, cut > max / 2 ? cut : max - 1).trim() + "…";
    }
}
