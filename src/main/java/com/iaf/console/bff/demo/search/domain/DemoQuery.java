package com.iaf.console.bff.demo.search.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * The search indexer's query syntax, as the demo stand-in reads it: free words and quoted phrases,
 * every one of which a resource must contain, and the terms {@code tag:<name>=<value>} (or
 * {@code tag:<name>} for any value), {@code type:}, {@code state:}, {@code region:},
 * {@code drift:true|false}, {@code created:} and {@code updated:} with a time, and a wfrn or wfrn
 * pattern ({@code *} matches the rest of a segment).
 *
 * <p>A time is a day ({@code 2026-10-01}, the whole day in UTC), a time ({@code 2026-10-01T12:00Z})
 * or an age counted back from now ({@code 30m}, {@code 24h}, {@code 7d}, {@code 2w}), compared with
 * {@code >}, {@code >=}, {@code <} or {@code <=}, or two of them as {@code a..b} with both ends kept
 * (the earlier end first, whichever way they are written). A day or an age alone is the day, or
 * the time since then; a time alone needs a comparison. Two terms on one field keep what both
 * keep, and a resource without the time is in no range.
 */
public record DemoQuery(List<String> words, Map<String, String> tags, Set<String> tagNames, String type, String state, String region, Boolean drift,
        List<String> wfrns, List<TimeRange> created, List<TimeRange> updated) {

    public static final List<String> STATES = List.of("pending", "active", "updating", "deleting", "deleted", "failed");
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern AGE = Pattern.compile("(\\d{1,6})([mhdw])");
    private static final String TIMES = "a time is a day (2026-10-01), a time (2026-10-01T12:00Z) or an age (30m, 24h, 7d, 2w)";

    public DemoQuery {
        words = List.copyOf(words);
        tags = Map.copyOf(tags);
        tagNames = Set.copyOf(tagNames);
        wfrns = List.copyOf(wfrns);
        created = List.copyOf(created);
        updated = List.copyOf(updated);
    }

    /**
     * The times a {@code created:} or {@code updated:} term keeps: from and to, each {@code null}
     * when that side is open, and each kept or not.
     */
    public record TimeRange(Instant from, boolean fromKept, Instant to, boolean toKept) {

        /** Whether the time is in the range; no time is in none. */
        public boolean keeps(Instant time) {
            if (time == null) {
                return false;
            }
            if (from != null && (fromKept ? time.isBefore(from) : !time.isAfter(from))) {
                return false;
            }
            return to == null || (toKept ? !time.isAfter(to) : time.isBefore(to));
        }
    }

    /** Reads a query as of the present moment. */
    public static DemoQuery parse(String q) {
        return parse(q, Instant.now());
    }

    /** Reads a query, counting ages back from {@code now}; a term with a value the indexer would refuse is a 400. */
    public static DemoQuery parse(String q, Instant now) {
        List<String> words = new ArrayList<>();
        Map<String, String> tags = new LinkedHashMap<>();
        Set<String> tagNames = new LinkedHashSet<>();
        List<String> wfrns = new ArrayList<>();
        List<TimeRange> created = new ArrayList<>();
        List<TimeRange> updated = new ArrayList<>();
        String type = null;
        String state = null;
        String region = null;
        Boolean drift = null;
        for (Token token : tokens(q == null ? "" : q)) {
            String text = token.text();
            if (text.isEmpty()) {
                continue;
            }
            if (token.phrase()) {
                words.add(text.toLowerCase(Locale.ROOT));
                continue;
            }
            int colon = text.indexOf(':');
            String key = colon < 0 ? "" : text.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : text.substring(colon + 1);
            switch (key) {
                case "wfrn" -> wfrns.add(text);
                case "tag" -> {
                    int eq = value.indexOf('=');
                    String name = (eq < 0 ? value : value.substring(0, eq)).strip();
                    if (name.isEmpty()) {
                        throw invalid("tag", "a tag term is tag:<name>=<value> or tag:<name>");
                    }
                    if (eq < 0) {
                        tagNames.add(name);
                    } else {
                        tags.put(name, value.substring(eq + 1));
                    }
                }
                case "type" -> type = required(value, "type");
                case "state" -> {
                    String wanted = required(value, "state").toLowerCase(Locale.ROOT);
                    if (!STATES.contains(wanted)) {
                        throw invalid("state", "state is one of: " + String.join(", ", STATES));
                    }
                    state = wanted;
                }
                case "region" -> region = required(value, "region");
                case "drift" -> {
                    String wanted = value.toLowerCase(Locale.ROOT);
                    if (!wanted.equals("true") && !wanted.equals("false")) {
                        throw invalid("drift", "drift is drift:true or drift:false");
                    }
                    drift = Boolean.valueOf(wanted);
                }
                case "created" -> created.add(timeRange("created", value, now));
                case "updated" -> updated.add(timeRange("updated", value, now));
                default -> words.add(text.toLowerCase(Locale.ROOT));
            }
        }
        return new DemoQuery(words, tags, tagNames, type, state, region, drift, wfrns, created, updated);
    }

    /** A piece of the query: a word or term, or a quoted phrase. */
    record Token(String text, boolean phrase) {
    }

    /** Splits on spaces outside double quotes; a token that starts with a quote is a phrase, quotes inside a term only group. */
    static List<Token> tokens(String q) {
        List<Token> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean phrase = false;
        boolean started = false;
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '"') {
                if (!started) {
                    phrase = true;
                    started = true;
                }
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (started) {
                    out.add(new Token(current.toString().strip(), phrase));
                }
                current.setLength(0);
                phrase = false;
                started = false;
                continue;
            }
            current.append(c);
            started = true;
        }
        if (started) {
            out.add(new Token(current.toString().strip(), phrase));
        }
        return out;
    }

    // ---- times

    private enum Kind {
        DAY, TIME, AGE
    }

    /** A day, a time or an age: a day starts and ends at midnight UTC, a time or an age is one instant. */
    private record Moment(Kind kind, Instant start, Instant end) {
    }

    /** The range of the value of a {@code created:} or {@code updated:} term. */
    static TimeRange timeRange(String field, String value, Instant now) {
        String v = value.strip();
        if (v.isEmpty()) {
            throw invalid(field, "a " + field + " term needs a time: " + TIMES);
        }
        for (String op : List.of(">=", "<=", ">", "<")) {
            if (v.startsWith(op)) {
                Moment m = moment(field, v.substring(op.length()), now);
                return switch (op) {
                    case ">" -> m.kind() == Kind.DAY ? new TimeRange(m.end(), true, null, false) : new TimeRange(m.start(), false, null, false);
                    case ">=" -> new TimeRange(m.start(), true, null, false);
                    case "<" -> new TimeRange(null, false, m.start(), false);
                    default -> new TimeRange(null, false, m.end(), m.kind() != Kind.DAY);
                };
            }
        }
        int dots = v.indexOf("..");
        if (dots >= 0) {
            Moment a = moment(field, v.substring(0, dots), now);
            Moment b = moment(field, v.substring(dots + 2), now);
            if (b.start().isBefore(a.start())) {
                Moment earlier = b;
                b = a;
                a = earlier;
            }
            return new TimeRange(a.start(), true, b.end(), b.kind() != Kind.DAY);
        }
        Moment m = moment(field, v, now);
        return switch (m.kind()) {
            case DAY -> new TimeRange(m.start(), true, m.end(), false);
            case AGE -> new TimeRange(m.start(), true, null, false);
            case TIME -> throw invalid(field, "a time alone needs a comparison: " + field + ":>=" + v + " or " + field + ":<" + v);
        };
    }

    private static Moment moment(String field, String text, Instant now) {
        String v = text.strip();
        if (DAY.matcher(v).matches()) {
            try {
                Instant start = LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
                return new Moment(Kind.DAY, start, start.plus(Duration.ofDays(1)));
            } catch (DateTimeParseException e) {
                throw invalid(field, "there is no day " + v);
            }
        }
        Matcher age = AGE.matcher(v.toLowerCase(Locale.ROOT));
        if (age.matches()) {
            long n = Long.parseLong(age.group(1));
            Duration span = switch (age.group(2)) {
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> Duration.ofDays(7 * n);
            };
            Instant at = now.minus(span);
            return new Moment(Kind.AGE, at, at);
        }
        if (v.length() > 10 && (v.charAt(10) == 'T' || v.charAt(10) == 't')) {
            try {
                Instant at = OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                return new Moment(Kind.TIME, at, at);
            } catch (DateTimeParseException e) {
                throw invalid(field, "a time is written 2026-10-01T12:00Z, with its offset");
            }
        }
        throw invalid(field, TIMES);
    }

    private static String required(String value, String term) {
        if (value == null || value.isBlank()) {
            throw invalid(term, "a " + term + " term needs a value: " + term + ":<value>");
        }
        return value.strip();
    }

    private static InvalidInputException invalid(String term, String message) {
        return new InvalidInputException("the query cannot be read: " + message, List.of(Problem.error("query." + term, "/q", message)));
    }
}
