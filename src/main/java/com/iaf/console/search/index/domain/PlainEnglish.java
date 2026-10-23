package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * Plain English read as a search, the copilot's fast path (WP 9.1): "failed vms tagged env=prod
 * created this week, newest first" becomes {@code type:vm state:failed tag:env=prod
 * created:>=2026-10-19} sorted by {@code -created}. It knows the platform's types (by name, plural
 * and title, forgiving a typo), the states and their everyday names, drift, times ({@link TimePhrases}),
 * sorts, the account's own tag names and values and regions, and the query syntax itself. Quoted
 * text is free text as typed; any other word it cannot place is a guess, which the caller keeps as
 * free text only while the search still finds something. It is deterministic and quick, so the
 * copilot answers as the person types; what it cannot read is left for the model behind the BFF.
 */
public final class PlainEnglish {

    private static final int MAX_TYPE_WORDS = 3;
    private static final Pattern SYNTAX = Pattern.compile("^(type|state|region|tag|drift|created|updated|wfrn):(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGION = Pattern.compile("^[a-z0-9-]{2,32}$");
    private static final Pattern TYPE = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final String TRIM = ",.;!?()[]{}";

    private static final Set<String> STOP = Set.of("show", "me", "list", "find", "get", "give", "display", "see", "search", "for", "all", "every",
            "everything", "any", "my", "our", "the", "a", "an", "of", "in", "on", "at", "to", "with", "which", "that", "are", "is", "was", "were", "be",
            "been", "there", "what", "where", "who", "how", "many", "much", "count", "number", "please", "can", "could", "you", "i", "we", "want",
            "need", "resources", "resource", "items", "things", "ones", "and", "those", "these", "them", "have", "has", "had", "currently", "now",
            "just", "only", "also", "some", "type", "types", "kind", "kinds", "console", "by", "last", "tagged", "tag", "tags", "label", "labelled",
            "labeled", "do", "does", "did", "it", "its", "their", "from", "state", "status", "region", "sorted", "ordered", "first", "whose", "exist",
            "exists", "existing", "current", "right", "here", "about", "named", "called", "like", "owned", "belonging", "belongs");
    private static final Set<String> TAG_LEADS = Set.of("tagged", "tag", "label", "labelled", "labeled");
    private static final Set<String> CREATED_VERBS = Set.of("created", "made", "launched", "built", "added", "provisioned", "deployed", "new", "spun",
            "started");
    private static final Set<String> UPDATED_VERBS = Set.of("updated", "changed", "modified", "edited", "touched");

    private static final Map<String, ResourceState> STATE_WORDS = Map.ofEntries(Map.entry("pending", ResourceState.PENDING),
            Map.entry("creating", ResourceState.PENDING), Map.entry("provisioning", ResourceState.PENDING), Map.entry("queued", ResourceState.PENDING),
            Map.entry("requested", ResourceState.PENDING), Map.entry("starting", ResourceState.PENDING), Map.entry("active", ResourceState.ACTIVE),
            Map.entry("running", ResourceState.ACTIVE), Map.entry("live", ResourceState.ACTIVE), Map.entry("healthy", ResourceState.ACTIVE),
            Map.entry("available", ResourceState.ACTIVE), Map.entry("ready", ResourceState.ACTIVE), Map.entry("online", ResourceState.ACTIVE),
            Map.entry("updating", ResourceState.UPDATING), Map.entry("modifying", ResourceState.UPDATING), Map.entry("resizing", ResourceState.UPDATING),
            Map.entry("upgrading", ResourceState.UPDATING), Map.entry("deleting", ResourceState.DELETING), Map.entry("removing", ResourceState.DELETING),
            Map.entry("terminating", ResourceState.DELETING), Map.entry("decommissioning", ResourceState.DELETING),
            Map.entry("deleted", ResourceState.DELETED), Map.entry("removed", ResourceState.DELETED), Map.entry("terminated", ResourceState.DELETED),
            Map.entry("destroyed", ResourceState.DELETED), Map.entry("decommissioned", ResourceState.DELETED), Map.entry("gone", ResourceState.DELETED),
            Map.entry("failed", ResourceState.FAILED), Map.entry("failing", ResourceState.FAILED), Map.entry("broken", ResourceState.FAILED),
            Map.entry("errored", ResourceState.FAILED), Map.entry("erroring", ResourceState.FAILED), Map.entry("error", ResourceState.FAILED),
            Map.entry("errors", ResourceState.FAILED), Map.entry("crashed", ResourceState.FAILED), Map.entry("unhealthy", ResourceState.FAILED));
    private static final List<Phrase<ResourceState>> STATE_PHRASES = List.of(new Phrase<>("in an error state", ResourceState.FAILED),
            new Phrase<>("in a failed state", ResourceState.FAILED), new Phrase<>("in failed state", ResourceState.FAILED),
            new Phrase<>("in error", ResourceState.FAILED), new Phrase<>("being created", ResourceState.PENDING),
            new Phrase<>("being provisioned", ResourceState.PENDING), new Phrase<>("being updated", ResourceState.UPDATING),
            new Phrase<>("being changed", ResourceState.UPDATING), new Phrase<>("being deleted", ResourceState.DELETING),
            new Phrase<>("being removed", ResourceState.DELETING), new Phrase<>("being destroyed", ResourceState.DELETING),
            new Phrase<>("shutting down", ResourceState.DELETING));
    private static final List<Phrase<SearchQuery.Sort>> SORTS = sorted(List.of(
            new Phrase<>("newest first", SearchQuery.Sort.CREATED_DESC), new Phrase<>("newest", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("newer first", SearchQuery.Sort.CREATED_DESC), new Phrase<>("latest first", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("latest", SearchQuery.Sort.CREATED_DESC), new Phrase<>("most recent first", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("most recent", SearchQuery.Sort.CREATED_DESC), new Phrase<>("most recently created", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("recently created", SearchQuery.Sort.CREATED_DESC), new Phrase<>("created recently", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("recent", SearchQuery.Sort.CREATED_DESC), new Phrase<>("new", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("youngest", SearchQuery.Sort.CREATED_DESC),
            new Phrase<>("oldest first", SearchQuery.Sort.CREATED), new Phrase<>("oldest", SearchQuery.Sort.CREATED),
            new Phrase<>("older first", SearchQuery.Sort.CREATED), new Phrase<>("earliest", SearchQuery.Sort.CREATED),
            new Phrase<>("least recently created", SearchQuery.Sort.CREATED),
            new Phrase<>("recently updated", SearchQuery.Sort.UPDATED_DESC), new Phrase<>("updated recently", SearchQuery.Sort.UPDATED_DESC),
            new Phrase<>("recently changed", SearchQuery.Sort.UPDATED_DESC), new Phrase<>("changed recently", SearchQuery.Sort.UPDATED_DESC),
            new Phrase<>("recently modified", SearchQuery.Sort.UPDATED_DESC), new Phrase<>("modified recently", SearchQuery.Sort.UPDATED_DESC),
            new Phrase<>("most recently updated", SearchQuery.Sort.UPDATED_DESC), new Phrase<>("most recently changed", SearchQuery.Sort.UPDATED_DESC),
            new Phrase<>("latest changes", SearchQuery.Sort.UPDATED_DESC),
            new Phrase<>("least recently updated", SearchQuery.Sort.UPDATED), new Phrase<>("least recently changed", SearchQuery.Sort.UPDATED),
            new Phrase<>("least recently modified", SearchQuery.Sort.UPDATED), new Phrase<>("stalest", SearchQuery.Sort.UPDATED),
            new Phrase<>("by name", SearchQuery.Sort.NAME), new Phrase<>("alphabetically", SearchQuery.Sort.NAME),
            new Phrase<>("alphabetical", SearchQuery.Sort.NAME), new Phrase<>("a to z", SearchQuery.Sort.NAME), new Phrase<>("a-z", SearchQuery.Sort.NAME),
            new Phrase<>("z to a", SearchQuery.Sort.NAME_DESC), new Phrase<>("z-a", SearchQuery.Sort.NAME_DESC),
            new Phrase<>("reverse alphabetical", SearchQuery.Sort.NAME_DESC), new Phrase<>("reverse alphabetically", SearchQuery.Sort.NAME_DESC),
            new Phrase<>("by name descending", SearchQuery.Sort.NAME_DESC)));
    private static final List<Phrase<Boolean>> DRIFT = sorted(List.of(new Phrase<>("no drift", false), new Phrase<>("without drift", false),
            new Phrase<>("not drifted", false), new Phrase<>("not drifting", false), new Phrase<>("in sync", false), new Phrase<>("in-sync", false),
            new Phrase<>("drift-free", false), new Phrase<>("drift free", false), new Phrase<>("free of drift", false),
            new Phrase<>("with drift", true), new Phrase<>("has drift", true), new Phrase<>("have drift", true), new Phrase<>("having drift", true),
            new Phrase<>("drifted", true), new Phrase<>("drifting", true), new Phrase<>("drift", true), new Phrase<>("out of sync", true),
            new Phrase<>("out-of-sync", true), new Phrase<>("changed outside", true), new Phrase<>("modified outside", true),
            new Phrase<>("edited outside", true)));

    private PlainEnglish() {
    }

    /** A phrase of whole words and what it means. */
    private record Phrase<T>(List<String> words, T meaning) {
        Phrase(String text, T meaning) {
            this(List.of(text.split(" ")), meaning);
        }
    }

    private static <T> List<Phrase<T>> sorted(List<Phrase<T>> phrases) {
        return phrases.stream().sorted(Comparator.comparingInt((Phrase<T> p) -> p.words().size()).reversed()).toList();
    }

    /** A word as typed (its punctuation at either end trimmed), lowercased, and whether quotes held it. */
    record Word(String raw, String lower, boolean quoted) {
    }

    /** A {@code created:} or {@code updated:} term and how it reads. */
    public record Time(String field, String value, String phrase) {
        public String term() {
            return field + ":" + value;
        }
    }

    /**
     * Reads a text. {@code within}, the type of the list the person is on, keeps the reading in that
     * type: another type named in the text is left out. A {@code within} that is not a type name is
     * a 400.
     */
    public static Reading read(String text, List<ResourceTypeHints> types, AccountVocabulary account, Instant now, String within) {
        if (within != null && !TYPE.matcher(within).matches()) {
            throw new InvalidInputException("type is not a type name", List.of(Problem.error("pattern", "/type", "a type is [a-z0-9-]{1,64}")));
        }
        Reader r = new Reader(words(text), types, account == null ? AccountVocabulary.EMPTY : account, now);
        if (within != null) {
            r.type = within;
            r.typeTitle = r.titleOf(within);
            r.typeGiven = true;
        }
        r.run();
        return r.reading();
    }

    /** The words of a text: blanks split them, double quotes (straight or curly) keep a phrase as one. */
    static List<Word> words(String text) {
        List<Word> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '“' || c == '”') {
                add(out, current, quoted);
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                add(out, current, false);
            } else {
                current.append(c);
            }
        }
        add(out, current, quoted);
        return out;
    }

    private static void add(List<Word> out, StringBuilder current, boolean quoted) {
        String s = current.toString().strip();
        current.setLength(0);
        if (!quoted) {
            int start = 0;
            int end = s.length();
            while (start < end && TRIM.indexOf(s.charAt(start)) >= 0) {
                start++;
            }
            while (end > start && TRIM.indexOf(s.charAt(end - 1)) >= 0) {
                end--;
            }
            s = s.substring(start, end);
        }
        if (!s.isEmpty()) {
            out.add(new Word(s, s.toLowerCase(Locale.ROOT), quoted));
        }
    }

    /** The walk over the words: at each word, the first reading that takes it, the longest phrase first. */
    private static final class Reader {
        private final List<Word> w;
        private final List<String> lower;
        /** The lowercased words with a quoted phrase as a quote mark, so no reading takes words from inside quotes. */
        private final List<String> unquoted;
        private final AccountVocabulary account;
        private final Instant now;
        private final Map<String, ResourceTypeHints> typeNames = new LinkedHashMap<>();
        private final Map<String, ResourceTypeHints> typesByName = new LinkedHashMap<>();

        String type;
        String typeTitle;
        boolean typeGiven;
        ResourceState state;
        Boolean drift;
        String region;
        final Map<String, String> tags = new LinkedHashMap<>();
        final List<Time> times = new ArrayList<>();
        final List<String> wfrns = new ArrayList<>();
        final List<String> phrases = new ArrayList<>();
        final List<String> guesses = new ArrayList<>();
        final List<String> unparsed = new ArrayList<>();
        SearchQuery.Sort sort;
        boolean corrected;

        Reader(List<Word> words, List<ResourceTypeHints> types, AccountVocabulary account, Instant now) {
            this.w = words;
            this.lower = words.stream().map(Word::lower).toList();
            this.unquoted = words.stream().map(x -> x.quoted() ? "\"" : x.lower()).toList();
            this.account = account;
            this.now = now;
            List<ResourceTypeHints> byName = types.stream().sorted(Comparator.comparing(ResourceTypeHints::name)).toList();
            for (ResourceTypeHints t : byName) {
                typesByName.put(t.name(), t);
                String title = t.title().toLowerCase(Locale.ROOT);
                for (String n : List.of(t.name(), t.plural(), t.name().replace('-', ' '), t.plural().replace('-', ' '), title, singular(title))) {
                    typeNames.putIfAbsent(n, t);
                    typeNames.putIfAbsent(n + "s", t);
                }
            }
        }

        void run() {
            int i = 0;
            while (i < w.size()) {
                Word word = w.get(i);
                if (word.quoted()) {
                    phrases.add(word.raw());
                    i++;
                    continue;
                }
                int n;
                if ((n = syntax(i)) > 0 || (n = sort(i)) > 0 || (n = drift(i)) > 0 || (n = time(i)) > 0 || (n = negation(i)) > 0 || (n = state(i)) > 0
                        || (n = type(i)) > 0 || (n = tag(i)) > 0 || (n = region(i)) > 0 || (n = union(i)) > 0 || (n = stop(i)) > 0) {
                    i += n;
                    continue;
                }
                guesses.add(word.raw().replace("\"", ""));
                i++;
            }
        }

        /** The query syntax typed as is: type:, state:, region:, tag:, drift:, created:, updated:, wfrn:. */
        private int syntax(int i) {
            Matcher m = SYNTAX.matcher(w.get(i).raw());
            if (!m.matches()) {
                return 0;
            }
            String prefix = m.group(1).toLowerCase(Locale.ROOT);
            String rest = m.group(2);
            if ("tag".equals(prefix)) {
                // a tag's name is lowercase; its value is as typed
                int eq = rest.indexOf('=');
                rest = eq < 0 ? rest.toLowerCase(Locale.ROOT) : rest.substring(0, eq).toLowerCase(Locale.ROOT) + rest.substring(eq);
            } else if (!"wfrn".equals(prefix)) {
                rest = rest.toLowerCase(Locale.ROOT);
            }
            String token = prefix + ":" + rest;
            try {
                QueryParser.parse("wfrn".equals(prefix) ? w.get(i).raw() : token, null, now);
            } catch (InvalidInputException e) {
                unparsed.add(w.get(i).raw());
                return 1;
            }
            String raw = w.get(i).raw();
            switch (prefix) {
                case "type" -> type(rest, titleOf(rest), false, raw);
                case "state" -> state(ResourceState.of(rest), raw);
                case "region" -> region(rest, raw);
                case "drift" -> drift(Boolean.parseBoolean(rest), raw);
                case "tag" -> {
                    int eq = rest.indexOf('=');
                    tag(eq < 0 ? rest : rest.substring(0, eq), eq < 0 ? "" : rest.substring(eq + 1), raw);
                }
                case "created", "updated" -> times.add(new Time(prefix, rest, TimePhrases.describe(rest)));
                default -> wfrns.add(raw);
            }
            return 1;
        }

        private int sort(int i) {
            for (Phrase<SearchQuery.Sort> p : SORTS) {
                if (at(i, p.words())) {
                    if (sort == null) {
                        sort = p.meaning();
                    } else if (sort != p.meaning()) {
                        unparsed.add(text(i, p.words().size()));
                    }
                    return p.words().size();
                }
            }
            return 0;
        }

        private int drift(int i) {
            for (Phrase<Boolean> p : DRIFT) {
                if (at(i, p.words())) {
                    int n = p.words().size();
                    if ("outside".equals(p.words().get(n - 1))) {
                        // changed outside [of] [the] [console|platform]
                        for (String optional : List.of("of", "the", "console")) {
                            if (optional.equals(word(i + n)) || ("console".equals(optional) && "platform".equals(word(i + n)))) {
                                n++;
                            }
                        }
                    }
                    drift(p.meaning(), text(i, n));
                    return n;
                }
            }
            return 0;
        }

        /** [created|updated] a time, or not updated in a time: a created time when no verb says otherwise. */
        private int time(int i) {
            String a = word(i);
            String field = null;
            boolean not = false;
            int j = i;
            if ("not".equals(a) && in(UPDATED_VERBS, word(i + 1))) {
                field = "updated";
                not = true;
                j = i + 2;
            } else if (in(CREATED_VERBS, a)) {
                field = "created";
                j = i + 1;
            } else if (in(UPDATED_VERBS, a)) {
                field = "updated";
                j = i + 1;
            }
            TimePhrases.Match m = TimePhrases.at(unquoted, j, now);
            if (m == null) {
                if (not) {
                    return 0;
                }
                // a verb with no time ("vms created by dana") says nothing a filter can hold
                return field == null ? 0 : 1;
            }
            String value = m.value();
            String phrase = m.phrase();
            if (not) {
                value = TimePhrases.negate(value);
                if (value == null) {
                    unparsed.add(text(i, j - i + m.words()));
                    return j - i + m.words();
                }
                phrase = TimePhrases.describe(value);
            }
            times.add(new Time(field == null ? "created" : field, value, phrase));
            return j - i + m.words();
        }

        /** not followed by what no filter can negate: left out. */
        private int negation(int i) {
            if (!"not".equals(word(i)) && !"except".equals(word(i)) && !"excluding".equals(word(i))) {
                return 0;
            }
            int n = word(i + 1) == null ? 1 : 2;
            unparsed.add(text(i, n));
            return n;
        }

        /** or asks for a union, which a filter cannot hold: left out, so the reading says so. */
        private int union(int i) {
            if (!"or".equals(word(i))) {
                return 0;
            }
            unparsed.add(w.get(i).raw());
            return 1;
        }

        private int state(int i) {
            for (Phrase<ResourceState> p : STATE_PHRASES) {
                if (at(i, p.words())) {
                    state(p.meaning(), text(i, p.words().size()));
                    return p.words().size();
                }
            }
            ResourceState s = word(i) == null ? null : STATE_WORDS.get(word(i));
            if (s == null) {
                return 0;
            }
            state(s, w.get(i).raw());
            return 1;
        }

        /** A type by name, plural or title, up to three words, or one word a typo away from one. */
        private int type(int i) {
            for (int n = Math.min(MAX_TYPE_WORDS, w.size() - i); n >= 1; n--) {
                if (anyQuoted(i, n)) {
                    continue;
                }
                ResourceTypeHints t = typeNames.get(String.join(" ", lower.subList(i, i + n)));
                if (t != null) {
                    type(t.name(), t.title(), false, text(i, n));
                    return n;
                }
            }
            String word = word(i);
            if (word == null || word.length() < 5 || STOP.contains(word)) {
                return 0;
            }
            ResourceTypeHints best = null;
            int bestDistance = Integer.MAX_VALUE;
            boolean tie = false;
            for (Map.Entry<String, ResourceTypeHints> e : typeNames.entrySet()) {
                String name = e.getKey();
                if (name.contains(" ") || !Fuzzy.close(word, name)) {
                    continue;
                }
                int d = Fuzzy.distance(word, name);
                if (d < bestDistance) {
                    best = e.getValue();
                    bestDistance = d;
                    tie = false;
                } else if (d == bestDistance && !best.name().equals(e.getValue().name())) {
                    tie = true;
                }
            }
            if (best == null || tie) {
                return 0;
            }
            type(best.name(), best.title(), true, w.get(i).raw());
            return 1;
        }

        /**
         * A tag: name=value, a known name followed by one of its values ("env prod"), a known
         * name:value, or after tagged/tag/label a name alone (has the tag) or a value only one name
         * holds.
         */
        private int tag(int i) {
            int j = i;
            boolean lead = in(TAG_LEADS, word(i)) && word(i + 1) != null;
            if (lead) {
                j = i + 1;
            }
            if (w.get(j).quoted()) {
                return 0;
            }
            String raw = w.get(j).raw();
            int eq = raw.indexOf('=');
            if (eq > 0) {
                String name = raw.substring(0, eq).toLowerCase(Locale.ROOT);
                if (!TagRules.validName(name)) {
                    return 0;
                }
                tag(name, raw.substring(eq + 1), text(i, j - i + 1));
                return j - i + 1;
            }
            int colon = raw.indexOf(':');
            if (colon > 0) {
                Optional<String> name = account.tagName(raw.substring(0, colon));
                if (name.isPresent() && colon + 1 < raw.length()) {
                    String value = raw.substring(colon + 1);
                    tag(name.get(), account.tagValue(name.get(), value).orElse(value), text(i, j - i + 1));
                    return j - i + 1;
                }
                return 0;
            }
            Optional<String> name = account.tagName(raw);
            if (name.isPresent()) {
                Word next = j + 1 < w.size() ? w.get(j + 1) : null;
                Optional<String> value = next == null || next.quoted() ? Optional.empty() : account.tagValue(name.get(), next.raw());
                if (value.isPresent()) {
                    tag(name.get(), value.get(), text(i, j - i + 2));
                    return j - i + 2;
                }
                if (lead) {
                    tag(name.get(), "", text(i, 2));
                    return 2;
                }
                return 0;
            }
            if (lead) {
                List<String> holders = account.tags().entrySet().stream()
                        .filter(e -> e.getValue().keySet().stream().anyMatch(v -> v.equalsIgnoreCase(raw))).map(Map.Entry::getKey).toList();
                if (holders.size() == 1) {
                    tag(holders.get(0), account.tagValue(holders.get(0), raw).orElse(raw), text(i, 2));
                    return 2;
                }
            }
            return 0;
        }

        /** A region of the account: in region x, region x, x region, in x, or x alone. */
        private int region(int i) {
            int j = i;
            if ("in".equals(word(j))) {
                j++;
            }
            boolean named = "region".equals(word(j));
            if (named) {
                j++;
            }
            String candidate = word(j);
            if (candidate == null) {
                return 0;
            }
            Optional<String> region = account.region(candidate);
            if (region.isEmpty() && named && REGION.matcher(candidate).matches()) {
                region = Optional.of(candidate);
            }
            if (region.isEmpty()) {
                return 0;
            }
            j++;
            if (!named && "region".equals(word(j))) {
                j++;
            }
            region(region.get(), text(i, j - i));
            return j - i;
        }

        private int stop(int i) {
            return in(STOP, word(i)) ? 1 : 0;
        }

        // what was read; a second, different reading of the same thing is left out

        private void type(String name, String title, boolean fuzzy, String words) {
            if (type == null) {
                type = name;
                typeTitle = title;
                corrected |= fuzzy;
            } else if (!type.equals(name)) {
                unparsed.add(words);
            }
        }

        private void state(ResourceState s, String words) {
            if (state == null) {
                state = s;
            } else if (state != s) {
                unparsed.add(words);
            }
        }

        private void drift(boolean d, String words) {
            if (drift == null) {
                drift = d;
            } else if (drift != d) {
                unparsed.add(words);
            }
        }

        private void region(String r, String words) {
            if (region == null) {
                region = r;
            } else if (!region.equals(r)) {
                unparsed.add(words);
            }
        }

        private void tag(String name, String value, String words) {
            String held = tags.get(name);
            if (held == null) {
                tags.put(name, value);
            } else if (!held.equals(value)) {
                unparsed.add(words);
            }
        }

        String titleOf(String name) {
            ResourceTypeHints t = typesByName.get(name);
            return (t == null ? ResourceTypeHints.unknown(name) : t).title();
        }

        private boolean at(int i, List<String> phrase) {
            if (i + phrase.size() > w.size() || anyQuoted(i, phrase.size())) {
                return false;
            }
            for (int k = 0; k < phrase.size(); k++) {
                if (!phrase.get(k).equals(lower.get(i + k))) {
                    return false;
                }
            }
            return true;
        }

        private boolean anyQuoted(int i, int n) {
            for (int k = i; k < i + n; k++) {
                if (w.get(k).quoted()) {
                    return true;
                }
            }
            return false;
        }

        /** The lowercased word at a position; null past either end or inside quotes. */
        private String word(int i) {
            return i >= 0 && i < w.size() && !w.get(i).quoted() ? lower.get(i) : null;
        }

        private static boolean in(Set<String> words, String word) {
            return word != null && words.contains(word);
        }

        private String text(int i, int n) {
            List<String> raw = new ArrayList<>();
            for (int k = i; k < Math.min(w.size(), i + n); k++) {
                raw.add(w.get(k).raw());
            }
            return String.join(" ", raw);
        }

        Reading reading() {
            return new Reading(type, typeTitle, typeGiven, state, drift, region, tags, times, wfrns, phrases, guesses, sort, unparsed, corrected);
        }

        private static String singular(String title) {
            return title.endsWith("s") ? title.substring(0, title.length() - 1) : title;
        }
    }

    /**
     * What a text was read as. {@link #q()} is the whole reading as one search query;
     * {@link #withoutGuesses()} the same reading with the guessed words left out, for when they
     * find nothing.
     */
    public record Reading(String type, String typeTitle, boolean typeGiven, ResourceState state, Boolean drift, String region,
            Map<String, String> tags, List<Time> times, List<String> wfrns, List<String> phrases, List<String> guesses, SearchQuery.Sort sort,
            List<String> unparsed, boolean corrected) {

        public Reading {
            tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            times = List.copyOf(times);
            wfrns = List.copyOf(wfrns);
            phrases = List.copyOf(phrases);
            guesses = List.copyOf(guesses);
            unparsed = List.copyOf(unparsed);
        }

        /** The reading as one search query, in the syntax of {@code /v1/search}. */
        public String q() {
            List<String> parts = new ArrayList<>();
            if (type != null) {
                parts.add("type:" + type);
            }
            if (state != null) {
                parts.add("state:" + state.wire());
            }
            if (region != null) {
                parts.add("region:" + region);
            }
            if (drift != null) {
                parts.add("drift:" + drift);
            }
            tags.forEach((k, v) -> parts.add(quoted(v.isEmpty() ? "tag:" + k : "tag:" + k + "=" + v)));
            String rest = filterQ();
            if (!rest.isEmpty()) {
                parts.add(rest);
            }
            return String.join(" ", parts);
        }

        /** What the list's own filters cannot hold: the times, the wfrn patterns and the free text. */
        public String filterQ() {
            List<String> parts = new ArrayList<>();
            times.forEach(t -> parts.add(t.term()));
            parts.addAll(wfrns);
            phrases.forEach(p -> parts.add(quoted(p)));
            parts.addAll(guesses);
            return String.join(" ", parts);
        }

        public boolean hasGuesses() {
            return !guesses.isEmpty();
        }

        /** The reading with its guesses left out. */
        public Reading withoutGuesses() {
            List<String> left = new ArrayList<>(unparsed);
            left.addAll(guesses);
            return new Reading(type, typeTitle, typeGiven, state, drift, region, tags, times, wfrns, phrases, List.of(), sort, left, corrected);
        }

        public Interpretation.Confidence confidence() {
            if (!unparsed.isEmpty()) {
                return Interpretation.Confidence.LOW;
            }
            boolean structured = (type != null && !typeGiven) || state != null || drift != null || region != null || !tags.isEmpty()
                    || !times.isEmpty() || !wfrns.isEmpty() || sort != null;
            if (!structured && phrases.isEmpty()) {
                return guesses.isEmpty() ? Interpretation.Confidence.MEDIUM : Interpretation.Confidence.LOW;
            }
            return !guesses.isEmpty() || corrected ? Interpretation.Confidence.MEDIUM : Interpretation.Confidence.HIGH;
        }

        /** How the reading reads back: "Virtual machines that failed, tagged env=prod, created in the last 7 days, newest first." */
        public String explanation() {
            List<String> clauses = new ArrayList<>();
            if (state != null) {
                clauses.add(switch (state) {
                    case FAILED -> "that failed";
                    case DELETED -> "that were deleted";
                    case DELETING -> "being deleted";
                    default -> "that are " + state.wire();
                });
            }
            if (drift != null) {
                clauses.add(drift ? "with drift" : "without drift");
            }
            if (region != null) {
                clauses.add("in region " + region);
            }
            List<String> valued = new ArrayList<>();
            tags.forEach((k, v) -> {
                if (v.isEmpty()) {
                    clauses.add("with a " + k + " tag");
                } else {
                    valued.add(k + "=" + v);
                }
            });
            if (!valued.isEmpty()) {
                clauses.add("tagged " + String.join(" and ", valued));
            }
            times.forEach(t -> clauses.add(t.field() + " " + t.phrase()));
            if (!wfrns.isEmpty()) {
                clauses.add("named like " + String.join(" or ", wfrns));
            }
            List<String> text = new ArrayList<>(phrases);
            text.addAll(guesses);
            if (!text.isEmpty()) {
                clauses.add("matching " + String.join(" and ", text.stream().map(t -> "“" + t + "”").toList()));
            }
            StringBuilder sb = new StringBuilder(typeTitle != null ? typeTitle : "Resources");
            for (int k = 0; k < clauses.size(); k++) {
                sb.append(k == 0 ? " " : ", ").append(clauses.get(k));
            }
            if (sort != null) {
                sb.append(", ").append(switch (sort) {
                    case CREATED_DESC -> "newest first";
                    case CREATED -> "oldest first";
                    case UPDATED_DESC -> "most recently updated first";
                    case UPDATED -> "least recently updated first";
                    case NAME -> "by name";
                    case NAME_DESC -> "by name, Z to A";
                    case SCORE -> "best match first";
                });
            }
            return sb.append('.').toString();
        }

        /** The answer of {@code /v1/search:interpret}, with how many resources the reading lists. */
        public Interpretation answer(long total) {
            Interpretation.Filter filter = new Interpretation.Filter(filterQ(), state == null ? null : state.wire(), drift, region, tags);
            return new Interpretation(q(), type, filter, sort == null ? null : sort.wire(), explanation(), confidence(), unparsed, total);
        }

        private static String quoted(String s) {
            String clean = s.replace("\"", "");
            return clean.chars().anyMatch(Character::isWhitespace) ? "\"" + clean + "\"" : clean;
        }
    }
}
