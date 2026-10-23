package com.iaf.console.bff.demo.search.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.iaf.console.kit.errors.InvalidInputException;

/**
 * The search indexer's plain English reading ({@code /v1/search:interpret}) as the demo stand-in
 * does it: deterministic, left to right, the longest phrase first. It knows the platform's types
 * (by name, plural and title, one typo forgiven from 5 letters), the states and their everyday
 * names ({@code broken}, {@code running}, {@code being deleted}), drift ({@code drifted},
 * {@code in sync}, {@code changed outside the console}), tags ({@code env=prod},
 * {@code tagged env=prod}, or one of the account's own tag names with one of its values,
 * {@code team payments}), the account's regions, sorts ({@code newest first}, {@code oldest
 * first}, {@code by name}), times ({@code today}, {@code in the last 24 hours}, {@code this week},
 * {@code since October 1}, {@code older than 90 days}, {@code not updated in 30 days}) and the
 * query syntax itself.
 *
 * <p>Quoted text is free text as typed. Any other word it cannot place is kept as free text while
 * the filter still lists something, and left out when it would list nothing; a union
 * ({@code or}), a negation ({@code not failed}), a second state, type, drift, region, tag value
 * or sort, and a term the syntax refuses are left out too. Confidence is high when every word
 * was understood; medium when some words became free text, a typo was fixed, or nothing but
 * filler was typed; low when words were left out, the text named another type than the list's,
 * or the reading is free text alone.
 */
public final class DemoInterpreter {

    /** A type as the platform names it. */
    public record TypeName(String type, String service, String plural, String title) {
    }

    /** What a reading may name: the platform's types, the account's tag names with their values, and the account's regions. */
    public record Vocabulary(List<TypeName> types, Map<String, Set<String>> tags, Set<String> regions) {
        public Vocabulary {
            types = List.copyOf(types);
            Map<String, Set<String>> copied = new LinkedHashMap<>();
            tags.forEach((name, values) -> copied.put(name, Set.copyOf(values)));
            tags = Map.copyOf(copied);
            regions = Set.copyOf(regions);
        }
    }

    /** The list's own filters a reading sets, each absent when it sets none; a tag with an empty value is any value. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Filter(String q, String state, Boolean drift, String region, Map<String, String> tags) {
        public Filter {
            tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        }
    }

    /** How many resources the list of a type (every type when {@code null}) shows with a filter. */
    @FunctionalInterface
    public interface Counter {
        long count(String type, Filter filter);
    }

    /** The reading, in the indexer's {@code Interpretation} shape. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reading(String q, String type, Filter filter, String sort, String explanation, String confidence, List<String> unparsed,
            long total) {
    }

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    private static final Pattern TAG_NAME = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern ISO_DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern YEAR = Pattern.compile("^\\d{4}$");
    private static final Pattern DAY_OF_MONTH = Pattern.compile("^(\\d{1,2})(st|nd|rd|th)?$");
    private static final Pattern COMPACT_AGE = Pattern.compile("^(\\d{1,6})([mhdw])$");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,6}$");
    private static final Set<String> TERMS = Set.of("type", "state", "region", "drift", "tag", "created", "updated", "wfrn");

    private static final Map<String, String> STATES = phrases(
            "pending", "pending", "being created", "pending", "creating", "pending", "provisioning", "pending",
            "active", "active", "running", "active", "healthy", "active", "up", "active", "live", "active", "ready", "active",
            "available", "active", "online", "active",
            "updating", "updating", "being updated", "updating",
            "deleting", "deleting", "being deleted", "deleting", "being removed", "deleting",
            "deleted", "deleted", "removed", "deleted", "gone", "deleted", "destroyed", "deleted",
            "failed", "failed", "failing", "failed", "broken", "failed", "errored", "failed", "in error", "failed", "unhealthy", "failed",
            "down", "failed", "crashed", "failed");
    private static final Map<String, String> DRIFT = phrases(
            "drifted", "true", "drifting", "true", "drift", "true", "with drift", "true", "has drift", "true", "have drift", "true",
            "out of sync", "true", "changed outside the console", "true", "changed outside of the console", "true",
            "changed outside console", "true", "modified outside the console", "true", "edited outside the console", "true",
            "in sync", "false", "not drifted", "false", "no drift", "false", "without drift", "false", "undrifted", "false");
    private static final Map<String, String> SORTS = phrases(
            "newest first", "-created", "newest", "-created", "latest first", "-created", "latest", "-created", "most recent first", "-created",
            "most recent", "-created", "recently created", "-created", "newest to oldest", "-created",
            "oldest first", "created", "oldest", "created", "oldest to newest", "created",
            "by name", "name", "sorted by name", "name", "ordered by name", "name", "sort by name", "name", "order by name", "name",
            "alphabetically", "name", "alphabetical", "name", "a to z", "name", "a-z", "name", "by name a to z", "name",
            "z to a", "-name", "z-a", "-name", "by name z to a", "-name", "reverse alphabetical", "-name", "by name descending", "-name",
            "recently updated", "-updated", "most recently updated", "-updated", "last updated first", "-updated", "recently changed", "-updated",
            "least recently updated", "updated");
    private static final Map<String, String> SORT_WORDS = Map.of("-created", "newest first", "created", "oldest first", "name", "by name",
            "-name", "by name, Z to A", "-updated", "most recently updated first", "updated", "least recently updated first");
    private static final Set<String> FILLER = Set.of("show", "me", "list", "find", "get", "give", "display", "search", "all", "any", "every",
            "everything", "the", "a", "an", "my", "our", "their", "its", "with", "that", "which", "who", "are", "is", "were", "was", "be", "been",
            "have", "has", "had", "in", "on", "at", "of", "for", "from", "to", "and", "please", "resources", "resource", "items", "item", "things",
            "ones", "those", "these", "some", "there", "what", "where", "whose", "currently", "now", "just", "only", "also", "i", "we", "you",
            "want", "need", "see", "can", "could", "would", "like", "by", "first", "tagged", "tag", "tags", "sorted", "ordered", "sort", "order",
            "created", "made", "launched", "added", "built", "updated", "modified", "changed", "touched", "edited", "region", "state", "type",
            "status", "this", "since", "ago");
    private static final Set<String> NEGATIONS = Set.of("not", "no", "non", "except", "excluding", "without", "never", "isn't", "aren't");
    private static final Set<String> CREATED = Set.of("created", "made", "launched", "added", "built");
    private static final Set<String> UPDATED = Set.of("updated", "modified", "changed", "touched", "edited");
    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1), Map.entry("one", 1),
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12));
    private static final Map<String, String> UNITS = Map.ofEntries(Map.entry("minute", "m"), Map.entry("minutes", "m"), Map.entry("min", "m"),
            Map.entry("mins", "m"), Map.entry("hour", "h"), Map.entry("hours", "h"), Map.entry("hr", "h"), Map.entry("hrs", "h"),
            Map.entry("day", "d"), Map.entry("days", "d"), Map.entry("week", "w"), Map.entry("weeks", "w"), Map.entry("wk", "w"),
            Map.entry("wks", "w"));
    private static final Map<String, String> UNIT_NAMES = Map.of("m", "minute", "h", "hour", "d", "day", "w", "week");

    private DemoInterpreter() {
    }

    /** Reads what a person typed, on the list of {@code listType} (none when {@code null}), as of {@code now}. */
    public static Reading read(String text, String listType, Vocabulary vocabulary, Instant now, Counter counter) {
        return new Reader(text, listType, vocabulary, now).read(counter);
    }

    /** A word as typed, without its trailing punctuation, in lowercase to compare, and whether it was quoted. */
    record Word(String typed, String clean, String key, boolean quoted) {
    }

    static List<Word> words(String text) {
        List<Word> out = new ArrayList<>();
        for (DemoQuery.Token token : DemoQuery.tokens(text == null ? "" : text)) {
            String typed = token.text();
            if (typed.isEmpty()) {
                continue;
            }
            if (token.phrase()) {
                out.add(new Word("\"" + typed + "\"", typed, typed.toLowerCase(Locale.ROOT), true));
                continue;
            }
            String clean = typed.replaceAll("[,.;!?]+$", "");
            if (clean.isEmpty()) {
                continue;
            }
            out.add(new Word(typed, clean, clean.toLowerCase(Locale.ROOT), false));
        }
        return out;
    }

    /** A word left out, where it was typed. */
    private record Left(int at, String typed) {
    }

    /** A time term the reading sets, and how the explanation says it. */
    private record Time(int used, String term, String said) {
    }

    /** A day the text names, and how many words it took. */
    private record Day(int used, LocalDate day) {
    }

    /** An age the text names (a number and a unit), and how many words it took. */
    private record Age(int used, int n, String unit) {
        String term() {
            return n + unit;
        }

        String said() {
            return n == 1 ? UNIT_NAMES.get(unit) : n + " " + UNIT_NAMES.get(unit) + "s";
        }
    }

    private static final class Reader {
        private final List<Word> words;
        private final String listType;
        private final Vocabulary vocabulary;
        private final Instant now;
        private final LocalDate today;
        private final List<Map.Entry<String, TypeName>> typeForms = new ArrayList<>();

        private String type;
        private boolean namedOtherType;
        private boolean typoFixed;
        private int understood;
        private String state;
        private Boolean drift;
        private String region;
        private String sort;
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final List<Time> times = new ArrayList<>();
        private final List<String> terms = new ArrayList<>();
        private final List<String> phrases = new ArrayList<>();
        private final List<Integer> candidates = new ArrayList<>();
        private final List<Left> left = new ArrayList<>();

        Reader(String text, String listType, Vocabulary vocabulary, Instant now) {
            this.words = words(text);
            this.listType = listType;
            this.vocabulary = vocabulary;
            this.now = now;
            this.today = LocalDate.ofInstant(now, ZoneOffset.UTC);
            for (TypeName t : vocabulary.types()) {
                for (String form : forms(t)) {
                    typeForms.add(Map.entry(form, t));
                }
            }
            typeForms.sort(Comparator.comparingInt((Map.Entry<String, TypeName> e) -> -e.getKey().split(" ").length));
        }

        Reading read(Counter counter) {
            int i = 0;
            while (i < words.size()) {
                i += step(i);
            }
            String listed = listType != null ? listType : type;
            List<String> accepted = new ArrayList<>();
            for (int at : candidates) {
                List<String> trial = new ArrayList<>(accepted);
                trial.add(words.get(at).clean());
                if (counter.count(listed, filter(trial)) > 0) {
                    accepted.add(words.get(at).clean());
                } else {
                    left.add(new Left(at, words.get(at).typed()));
                }
            }
            Filter filter = filter(accepted);
            long total = counter.count(listed, filter);
            left.sort(Comparator.comparingInt(Left::at));
            List<String> unparsed = left.stream().map(Left::typed).toList();
            boolean free = !phrases.isEmpty() || !accepted.isEmpty();
            String confidence = !unparsed.isEmpty() || namedOtherType || (understood == 0 && free) ? LOW
                    : free || typoFixed || understood == 0 ? MEDIUM : HIGH;
            return new Reading(query(listed, filter), listed, filter, sort, explanation(listed, accepted), confidence,
                    unparsed.isEmpty() ? null : unparsed, total);
        }

        /** Reads what starts at word i; the number of words taken, at least one. */
        private int step(int i) {
            Word w = words.get(i);
            if (w.quoted()) {
                phrases.add(w.typed());
                return 1;
            }
            int used;
            if ((used = syntax(i)) > 0 || (used = tagEquals(i)) > 0 || (used = timeAt(i)) > 0 || (used = driftAt(i)) > 0
                    || (used = leftOut(i)) > 0 || (used = sortAt(i)) > 0 || (used = stateAt(i)) > 0 || (used = typeAt(i, false)) > 0
                    || (used = regionAt(i)) > 0 || (used = taggedAt(i)) > 0) {
                return used;
            }
            if (FILLER.contains(w.key())) {
                return 1;
            }
            if ((used = typeAt(i, true)) > 0) {
                return used;
            }
            candidates.add(i);
            return 1;
        }

        // ---- the query syntax

        private int syntax(int i) {
            Word w = words.get(i);
            int colon = w.clean().indexOf(':');
            if (colon <= 0 || !TERMS.contains(w.key().substring(0, colon))) {
                return 0;
            }
            String key = w.key().substring(0, colon);
            String value = w.clean().substring(colon + 1).strip();
            switch (key) {
                case "type" -> {
                    TypeName t = vocabulary.types().stream().filter(n -> n.type().equals(value.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
                    if (t == null) {
                        leave(i, 1);
                    } else {
                        named(t, i, 1);
                    }
                }
                case "state" -> {
                    String s = value.toLowerCase(Locale.ROOT);
                    if (DemoQuery.STATES.contains(s)) {
                        state(s, i, 1);
                    } else {
                        leave(i, 1);
                    }
                }
                case "region" -> {
                    if (value.isEmpty()) {
                        leave(i, 1);
                    } else {
                        region(value, i, 1);
                    }
                }
                case "drift" -> {
                    String d = value.toLowerCase(Locale.ROOT);
                    if (d.equals("true") || d.equals("false")) {
                        drift(Boolean.valueOf(d), i, 1);
                    } else {
                        leave(i, 1);
                    }
                }
                case "tag" -> {
                    int eq = value.indexOf('=');
                    String name = (eq < 0 ? value : value.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
                    if (!TAG_NAME.matcher(name).matches()) {
                        leave(i, 1);
                    } else {
                        tag(name, eq < 0 ? "" : value.substring(eq + 1), i, 1);
                    }
                }
                case "created", "updated" -> {
                    try {
                        DemoQuery.timeRange(key, value, now);
                        times.add(new Time(1, key + ":" + value, key + " " + said(value)));
                        understood++;
                    } catch (InvalidInputException e) {
                        leave(i, 1);
                    }
                }
                default -> {
                    terms.add(w.clean());
                    understood++;
                }
            }
            return 1;
        }

        // ---- tags

        /** {@code name=value} (an empty value is any value), and {@code name = value} with the account's own tag name. */
        private int tagEquals(int i) {
            Word w = words.get(i);
            int eq = w.clean().indexOf('=');
            if (eq > 0) {
                String name = w.clean().substring(0, eq).strip().toLowerCase(Locale.ROOT);
                if (!TAG_NAME.matcher(name).matches()) {
                    leave(i, 1);
                } else {
                    tag(name, w.clean().substring(eq + 1).strip(), i, 1);
                }
                return 1;
            }
            if (i + 2 < words.size() && words.get(i + 1).key().equals("=") && vocabulary.tags().containsKey(w.key()) && !words.get(i + 2).quoted()) {
                tag(w.key(), words.get(i + 2).clean(), i, 3);
                return 3;
            }
            return 0;
        }

        /** {@code tagged <name>} with one of the account's tag names (any value), or {@code <name> <value>} with its name and one of its values. */
        private int taggedAt(int i) {
            Word w = words.get(i);
            if ((w.key().equals("tagged") || w.key().equals("tag")) && i + 1 < words.size()) {
                Word name = words.get(i + 1);
                if (!name.quoted() && vocabulary.tags().containsKey(name.key()) && !(i + 2 < words.size() && value(name.key(), words.get(i + 2)) != null)) {
                    tag(name.key(), "", i, 2);
                    return 2;
                }
                return 0;
            }
            if (vocabulary.tags().containsKey(w.key()) && i + 1 < words.size()) {
                String value = value(w.key(), words.get(i + 1));
                if (value != null) {
                    tag(w.key(), value, i, 2);
                    return 2;
                }
            }
            return 0;
        }

        /** The account's own spelling of the word as a value of the tag, or null. */
        private String value(String name, Word word) {
            if (word.quoted()) {
                return null;
            }
            for (String value : vocabulary.tags().getOrDefault(name, Set.of())) {
                if (value.equalsIgnoreCase(word.clean())) {
                    return value;
                }
            }
            return null;
        }

        private void tag(String name, String value, int at, int used) {
            String had = tags.get(name);
            if (had != null && !had.equals(value)) {
                leave(at, used);
                return;
            }
            tags.put(name, value);
            understood++;
        }

        // ---- times

        private int timeAt(int i) {
            Word w = words.get(i);
            if (w.key().equals("not") || w.key().equals("untouched") || w.key().equals("unchanged") || w.key().equals("stale")) {
                int at = i + 1;
                if (w.key().equals("not")) {
                    if (at >= words.size() || !UPDATED.contains(words.get(at).key())) {
                        return 0;
                    }
                    at++;
                }
                if (at < words.size() && Set.of("in", "for", "within", "since").contains(words.get(at).key())) {
                    at++;
                }
                at += skip(at, "the");
                at += skip(at, "last");
                at += skip(at, "past");
                Age age = age(at);
                if (age == null) {
                    return 0;
                }
                times.add(new Time(0, "updated:<" + age.term(), "not updated in " + age.said()));
                understood++;
                return at + age.used() - i;
            }
            String field = "created";
            int at = i;
            if (CREATED.contains(w.key()) || UPDATED.contains(w.key())) {
                field = CREATED.contains(w.key()) ? "created" : "updated";
                at++;
            }
            Time time = at < words.size() ? time(at, field, at > i) : null;
            if (time == null) {
                return 0;
            }
            times.add(time);
            understood++;
            return at - i + time.used();
        }

        /** A time phrase at word i, for the field; a day alone only right after the field's own word. */
        private Time time(int i, String field, boolean afterField) {
            String key = words.get(i).key();
            String next = i + 1 < words.size() ? words.get(i + 1).key() : "";
            switch (key) {
                case "today" -> {
                    return new Time(1, field + ":" + today, field + " today");
                }
                case "yesterday" -> {
                    return new Time(1, field + ":" + today.minusDays(1), field + " yesterday");
                }
                case "this" -> {
                    LocalDate since = switch (next) {
                        case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                        case "month" -> today.withDayOfMonth(1);
                        case "year" -> today.withDayOfYear(1);
                        default -> null;
                    };
                    return since == null ? null : new Time(2, field + ":>=" + since, field + " this " + next + " (since " + since + ")");
                }
                case "since", "after", "before", "on" -> {
                    Day day = day(i + 1);
                    if (day == null) {
                        return null;
                    }
                    String op = switch (key) {
                        case "since" -> ">=";
                        case "after" -> ">";
                        case "before" -> "<";
                        default -> "";
                    };
                    return new Time(1 + day.used(), field + ":" + op + day.day(), field + " " + key + " " + day.day());
                }
                case "between" -> {
                    Day from = day(i + 1);
                    if (from == null || i + 1 + from.used() >= words.size() || !words.get(i + 1 + from.used()).key().equals("and")) {
                        return null;
                    }
                    Day to = day(i + 2 + from.used());
                    if (to == null) {
                        return null;
                    }
                    return new Time(2 + from.used() + to.used(), field + ":" + from.day() + ".." + to.day(),
                            field + " between " + from.day() + " and " + to.day());
                }
                case "older", "more" -> {
                    if (!next.equals("than")) {
                        return null;
                    }
                    Age age = age(i + 2);
                    int ago = age == null ? 0 : skip(i + 2 + age.used(), "ago");
                    if (age == null || (key.equals("more") && ago == 0)) {
                        return null;
                    }
                    return new Time(2 + age.used() + ago, field + ":<" + age.term(), field + " more than " + age.said() + " ago");
                }
                default -> {
                    int at = i;
                    at += skip(at, "in") + skip(at, "within") + skip(at, "during") + skip(at, "over");
                    at += skip(at, "the");
                    boolean prefixed = at > i;
                    if (at < words.size() && (words.get(at).key().equals("last") || words.get(at).key().equals("past"))) {
                        Age age = age(at + 1);
                        if (age == null) {
                            Age one = unit(at + 1, 1);
                            age = one;
                        }
                        if (age != null) {
                            if (!prefixed && words.get(at).key().equals("last") && age.n() == 1 && age.used() == 1 && age.unit().equals("w")) {
                                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                                LocalDate sunday = monday.plusDays(6);
                                return new Time(2, field + ":" + monday + ".." + sunday, field + " last week (" + monday + " to " + sunday + ")");
                            }
                            return new Time(at + 1 + age.used() - i, field + ":" + age.term(), field + " in the last " + age.said());
                        }
                    }
                    if (afterField) {
                        Day day = day(i);
                        if (day != null) {
                            return new Time(day.used(), field + ":" + day.day(), field + " on " + day.day());
                        }
                    }
                    return null;
                }
            }
        }

        /** A number and a unit ({@code 24 hours}, {@code a day}, {@code 7d}) at word i. */
        private Age age(int i) {
            if (i >= words.size()) {
                return null;
            }
            String key = words.get(i).key();
            Matcher compact = COMPACT_AGE.matcher(key);
            if (compact.matches()) {
                return new Age(1, Integer.parseInt(compact.group(1)), compact.group(2));
            }
            Integer n = NUMBER.matcher(key).matches() ? Integer.valueOf(key) : NUMBER_WORDS.get(key);
            if (n == null || n < 1) {
                return null;
            }
            Age unit = unit(i + 1, n);
            return unit == null ? null : new Age(1 + unit.used(), n, unit.unit());
        }

        private Age unit(int i, int n) {
            if (i >= words.size()) {
                return null;
            }
            String unit = UNITS.get(words.get(i).key());
            return unit == null ? null : new Age(1, n, unit);
        }

        /** A day at word i: {@code 2026-10-01}, {@code October 1}, {@code Oct 1st, 2026}, {@code 1 October}, {@code today} or {@code yesterday}. */
        private Day day(int i) {
            if (i >= words.size() || words.get(i).quoted()) {
                return null;
            }
            String key = words.get(i).key();
            if (key.equals("today")) {
                return new Day(1, today);
            }
            if (key.equals("yesterday")) {
                return new Day(1, today.minusDays(1));
            }
            if (ISO_DAY.matcher(key).matches()) {
                try {
                    return new Day(1, LocalDate.parse(key));
                } catch (RuntimeException e) {
                    return null;
                }
            }
            Month month = month(key);
            if (month != null && i + 1 < words.size()) {
                Matcher dom = DAY_OF_MONTH.matcher(words.get(i + 1).key());
                if (dom.matches()) {
                    return dated(month, Integer.parseInt(dom.group(1)), i + 2, 2);
                }
            }
            Matcher dom = DAY_OF_MONTH.matcher(key);
            if (dom.matches() && i + 1 < words.size()) {
                Month after = month(words.get(i + 1).key());
                if (after != null) {
                    return dated(after, Integer.parseInt(dom.group(1)), i + 2, 2);
                }
            }
            return null;
        }

        /** The day of the month, in the year that follows at word i, else the latest one not after today. */
        private Day dated(Month month, int dayOfMonth, int i, int used) {
            try {
                if (i < words.size() && YEAR.matcher(words.get(i).key()).matches()) {
                    return new Day(used + 1, LocalDate.of(Integer.parseInt(words.get(i).key()), month, dayOfMonth));
                }
                LocalDate day = LocalDate.of(today.getYear(), month, dayOfMonth);
                return new Day(used, day.isAfter(today) ? day.minusYears(1) : day);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static Month month(String key) {
            for (Month m : Month.values()) {
                String name = m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
                if (key.equals(name) || (key.length() >= 3 && name.startsWith(key) && (key.length() == 3 || key.equals("sept")))) {
                    return m;
                }
            }
            return null;
        }

        private int skip(int i, String word) {
            return i < words.size() && words.get(i).key().equals(word) ? 1 : 0;
        }

        // ---- drift, sorts, states, types, regions

        private int driftAt(int i) {
            Map.Entry<Integer, String> found = longest(i, DRIFT);
            if (found == null) {
                return 0;
            }
            Boolean wanted = Boolean.valueOf(found.getValue());
            drift(wanted, i, found.getKey());
            return found.getKey();
        }

        private void drift(Boolean wanted, int at, int used) {
            if (drift != null && !drift.equals(wanted)) {
                leave(at, used);
                return;
            }
            drift = wanted;
            understood++;
        }

        private int sortAt(int i) {
            Map.Entry<Integer, String> found = longest(i, SORTS);
            if (found == null) {
                return 0;
            }
            if (sort != null && !sort.equals(found.getValue())) {
                leave(i, found.getKey());
            } else {
                sort = found.getValue();
                understood++;
            }
            return found.getKey();
        }

        private int stateAt(int i) {
            Map.Entry<Integer, String> found = longest(i, STATES);
            if (found == null) {
                return 0;
            }
            state(found.getValue(), i, found.getKey());
            return found.getKey();
        }

        private void state(String wanted, int at, int used) {
            if (state != null && !state.equals(wanted)) {
                leave(at, used);
                return;
            }
            state = wanted;
            understood++;
        }

        /** A type by its name, plural or title; with {@code typos}, one typo forgiven in a form of 5 letters or more. */
        private int typeAt(int i, boolean typos) {
            for (Map.Entry<String, TypeName> form : typeForms) {
                String[] parts = form.getKey().split(" ");
                if (i + parts.length > words.size()) {
                    continue;
                }
                StringBuilder typed = new StringBuilder();
                boolean quoted = false;
                for (int k = 0; k < parts.length; k++) {
                    typed.append(k == 0 ? "" : " ").append(words.get(i + k).key());
                    quoted |= words.get(i + k).quoted();
                }
                if (quoted) {
                    continue;
                }
                String text = typed.toString();
                boolean exact = text.equals(form.getKey());
                if (exact || (typos && form.getKey().length() >= 5 && text.length() >= 5 && Typos.within(text, form.getKey(), 1))) {
                    typoFixed |= !exact;
                    named(form.getValue(), i, parts.length);
                    return parts.length;
                }
            }
            return 0;
        }

        private void named(TypeName t, int at, int used) {
            if (listType != null) {
                if (listType.equals(t.type())) {
                    understood++;
                } else {
                    namedOtherType = true;
                    leave(at, used);
                }
                return;
            }
            if (type != null && !type.equals(t.type())) {
                leave(at, used);
                return;
            }
            type = t.type();
            understood++;
        }

        private int regionAt(int i) {
            int at = i + skip(i, "in");
            at += skip(at, "region");
            if (at >= words.size() || words.get(at).quoted()) {
                return 0;
            }
            for (String known : vocabulary.regions()) {
                if (known.equalsIgnoreCase(words.get(at).clean())) {
                    region(known, i, at - i + 1);
                    return at - i + 1;
                }
            }
            return 0;
        }

        private void region(String wanted, int at, int used) {
            if (region != null && !region.equals(wanted)) {
                leave(at, used);
                return;
            }
            region = wanted;
            understood++;
        }

        // ---- what a filter cannot hold

        /** A negation or a union, with what it applies to: the next phrase or word. */
        private int leftOut(int i) {
            String key = words.get(i).key();
            if (!NEGATIONS.contains(key) && !key.equals("or")) {
                return 0;
            }
            int next = i + 1 >= words.size() ? 0 : Math.max(1, Math.max(phraseLength(i + 1, STATES), Math.max(phraseLength(i + 1, DRIFT),
                    Math.max(phraseLength(i + 1, SORTS), typeLength(i + 1)))));
            leave(i, 1 + next);
            return 1 + next;
        }

        private int phraseLength(int i, Map<String, String> table) {
            Map.Entry<Integer, String> found = longest(i, table);
            return found == null ? 0 : found.getKey();
        }

        private int typeLength(int i) {
            for (Map.Entry<String, TypeName> form : typeForms) {
                String[] parts = form.getKey().split(" ");
                if (i + parts.length <= words.size() && joined(i, parts.length).equals(form.getKey())) {
                    return parts.length;
                }
            }
            return 0;
        }

        private void leave(int at, int used) {
            StringBuilder typed = new StringBuilder();
            for (int k = at; k < Math.min(words.size(), at + used); k++) {
                typed.append(k == at ? "" : " ").append(words.get(k).typed());
            }
            left.add(new Left(at, typed.toString()));
        }

        /** The longest phrase of the table that starts at word i: its length and its value; null when none does. */
        private Map.Entry<Integer, String> longest(int i, Map<String, String> table) {
            Map.Entry<Integer, String> best = null;
            for (Map.Entry<String, String> phrase : table.entrySet()) {
                int n = phrase.getKey().split(" ").length;
                if (i + n <= words.size() && (best == null || n > best.getKey()) && joined(i, n).equals(phrase.getKey())) {
                    best = Map.entry(n, phrase.getValue());
                }
            }
            return best;
        }

        private String joined(int i, int n) {
            StringBuilder out = new StringBuilder();
            for (int k = i; k < i + n; k++) {
                if (words.get(k).quoted()) {
                    return "";
                }
                out.append(k == i ? "" : " ").append(words.get(k).key());
            }
            return out.toString();
        }

        // ---- the answer

        private Filter filter(List<String> free) {
            List<String> q = new ArrayList<>();
            times.forEach(t -> q.add(t.term()));
            q.addAll(terms);
            q.addAll(phrases);
            q.addAll(free);
            return new Filter(q.isEmpty() ? null : String.join(" ", q), state, drift, region, tags);
        }

        /** The whole reading as one query of {@code /v1/search}: the type, state, region, drift and tag terms, then the filter's q. */
        private String query(String listed, Filter filter) {
            List<String> q = new ArrayList<>();
            if (listed != null) {
                q.add("type:" + listed);
            }
            if (state != null) {
                q.add("state:" + state);
            }
            if (region != null) {
                q.add("region:" + region);
            }
            if (drift != null) {
                q.add("drift:" + drift);
            }
            tags.forEach((name, value) -> q.add(value.isEmpty() ? "tag:" + name : "tag:" + name + "=" + quoted(value)));
            if (filter.q() != null) {
                q.add(filter.q());
            }
            return String.join(" ", q);
        }

        private static String quoted(String value) {
            return value.chars().anyMatch(c -> Character.isWhitespace(c) || c == '"') ? "\"" + value.replace("\"", "") + "\"" : value;
        }

        /** The reading as one sentence: "Virtual machines that failed, tagged env=prod, created this week (since 2026-10-19), newest first." */
        private String explanation(String listed, List<String> free) {
            String subject = "Resources";
            if (listed != null) {
                TypeName t = vocabulary.types().stream().filter(n -> n.type().equals(listed)).findFirst().orElse(null);
                subject = capitalised(t == null || t.title() == null ? listed : t.title());
            }
            List<String> clauses = new ArrayList<>();
            if (state != null) {
                clauses.add(switch (state) {
                    case "failed" -> "that failed";
                    case "deleting" -> "that are being deleted";
                    case "deleted" -> "that were deleted";
                    default -> "that are " + state;
                });
            }
            if (drift != null) {
                clauses.add(drift ? "that drifted" : "in sync");
            }
            if (!tags.isEmpty()) {
                List<String> said = new ArrayList<>();
                tags.forEach((name, value) -> said.add(value.isEmpty() ? name + " (any value)" : name + "=" + value));
                clauses.add("tagged " + and(said));
            }
            if (region != null) {
                clauses.add("in " + region);
            }
            times.forEach(t -> clauses.add(t.said()));
            terms.forEach(t -> clauses.add("with " + t));
            List<String> text = new ArrayList<>(phrases);
            free.forEach(w -> text.add("\"" + w + "\""));
            if (!text.isEmpty()) {
                clauses.add("matching " + and(text));
            }
            StringBuilder out = new StringBuilder(subject);
            if (!clauses.isEmpty()) {
                out.append(' ').append(String.join(", ", clauses));
            }
            if (sort != null) {
                out.append(", ").append(SORT_WORDS.get(sort));
            }
            return out.append('.').toString();
        }

        private static String and(List<String> items) {
            if (items.size() == 1) {
                return items.get(0);
            }
            return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
        }
    }

    // ---- pieces

    /** How a typed {@code created:} or {@code updated:} value reads in words. */
    static String said(String value) {
        String v = value.strip();
        Matcher age = Pattern.compile("^(<=|>=|<|>)?(\\d{1,6})([mhdw])$").matcher(v.toLowerCase(Locale.ROOT));
        if (age.matches()) {
            int n = Integer.parseInt(age.group(2));
            String span = n == 1 ? UNIT_NAMES.get(age.group(3)) : n + " " + UNIT_NAMES.get(age.group(3)) + "s";
            String op = age.group(1) == null ? "" : age.group(1);
            return op.startsWith("<") ? "more than " + span + " ago" : "in the last " + span;
        }
        if (v.contains("..")) {
            return "between " + v.substring(0, v.indexOf("..")) + " and " + v.substring(v.indexOf("..") + 2);
        }
        for (String op : List.of(">=", "<=", ">", "<")) {
            if (v.startsWith(op)) {
                String rest = v.substring(op.length());
                return switch (op) {
                    case ">=" -> "since " + rest;
                    case ">" -> "after " + rest;
                    case "<" -> "before " + rest;
                    default -> "on or before " + rest;
                };
            }
        }
        return "on " + v;
    }

    /** The names a type goes by, in lowercase: its name, plural and title, the title in the singular, with hyphens as spaces too. */
    static Set<String> forms(TypeName t) {
        Set<String> forms = new LinkedHashSet<>();
        for (String name : new String[] {t.type(), t.plural(), t.title()}) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String lower = name.strip().toLowerCase(Locale.ROOT);
            forms.add(lower);
            forms.add(lower.replace('-', ' '));
            if (lower.endsWith("s") && lower.length() > 3) {
                forms.add(lower.substring(0, lower.length() - 1));
            }
        }
        return forms;
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Map<String, String> phrases(String... pairs) {
        Map<String, String> table = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            table.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(table);
    }
}
