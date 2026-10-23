package com.iaf.console.drift.drift.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Who most likely owns an object the console does not manage (WP 5.3), in three steps of
 * confidence:
 * <ol>
 * <li>{@code high}: a tag or attribute such as {@code owner}, {@code team} or {@code account}
 * names an account's team, id or name;</li>
 * <li>{@code medium}: the object's name holds an account's name or its team's;</li>
 * <li>{@code low}: the object shares a folder, network, cluster or pool with the managed resources
 * of one account and no other (a place several accounts use says nothing either way); failing
 * that, one account and no other manages resources of the type in the region.</li>
 * </ol>
 * The guesses read the fields the type's {@code observe.map} gives the object; a map that names the
 * object's name, tags and folder gives the best guesses.
 * A step that points at more than one account says nothing, and the next step is tried; no step
 * pointing anywhere leaves the owner unknown. Pure: the accounts and the managed neighbours come
 * from the caller.
 */
public final class OwnerGuesses {

    /** The attribute and tag keys that name an owner, compared without case or separators. */
    static final Set<String> OWNER_KEYS = Set.of("owner", "team", "owningteam", "account", "accountid");
    /** The containers whose sharing says something about ownership; an environment is shared by everyone, so it is not one. */
    public static final List<String> PLACES = List.of("folder", "network", "cluster", "pool", "resourcePool", "datastore", "subnet", "vpc",
            "namespace", "project");
    /** Names shorter than this match too much by accident ("eu", "qa"). */
    private static final int SHORTEST_NAME = 3;

    private final List<AccountInfo> accounts;
    private final Neighbours neighbours;

    public OwnerGuesses(List<AccountInfo> accounts, Neighbours neighbours) {
        this.accounts = accounts == null ? List.of() : List.copyOf(accounts);
        this.neighbours = neighbours == null ? new Neighbours() : neighbours;
    }

    /** The best guess for an object of this name with these provider attributes; null when nothing points at one account. */
    public OwnerGuess guess(String name, ObjectNode observed) {
        OwnerGuess guess = byTags(observed);
        if (guess == null) {
            guess = byName(name);
        }
        if (guess == null) {
            guess = byPlace(observed);
        }
        if (guess == null) {
            guess = byType();
        }
        return guess;
    }

    private OwnerGuess byTags(ObjectNode observed) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        if (observed != null) {
            observed.properties().forEach(e -> ownerTag(e.getKey(), e.getValue(), found));
            for (String bag : List.of("tags", "labels")) {
                JsonNode tags = observed.get(bag);
                if (tags != null && tags.isObject()) {
                    tags.properties().forEach(e -> ownerTag(e.getKey(), e.getValue(), found));
                }
            }
        }
        return single(found, OwnerGuess.HIGH);
    }

    private void ownerTag(String key, JsonNode value, Map<String, List<String>> found) {
        if (value == null || !value.isValueNode() || value.asText().isBlank() || !OWNER_KEYS.contains(squash(key))) {
            return;
        }
        String text = value.asText().strip();
        for (AccountInfo account : accounts) {
            if (text.equalsIgnoreCase(account.accountId()) || text.equalsIgnoreCase(account.team()) || sameName(text, account.name())) {
                found.computeIfAbsent(account.accountId(), k -> new ArrayList<>()).add("Tagged " + key + "=" + text);
            }
        }
    }

    private OwnerGuess byName(String name) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        List<String> words = words(name);
        if (!words.isEmpty()) {
            for (AccountInfo account : accounts) {
                if (account.name() != null && account.name().strip().length() >= SHORTEST_NAME && holds(words, words(account.name()))) {
                    found.computeIfAbsent(account.accountId(), k -> new ArrayList<>())
                            .add("The name " + name + " contains the account's name, " + account.name());
                } else if (account.team() != null && account.team().strip().length() >= SHORTEST_NAME && holds(words, words(account.team()))) {
                    found.computeIfAbsent(account.accountId(), k -> new ArrayList<>())
                            .add("The name " + name + " contains the team's name, " + account.team());
                }
            }
        }
        return single(found, OwnerGuess.MEDIUM);
    }

    private OwnerGuess byPlace(ObjectNode observed) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        if (observed != null) {
            for (String place : PLACES) {
                String value = Neighbours.text(observed.get(place));
                if (value == null) {
                    continue;
                }
                Set<String> there = neighbours.accountsAt(place, value);
                if (there.size() == 1) {
                    String accountId = there.iterator().next();
                    AccountInfo account = account(accountId);
                    String whose = account != null && account.name() != null ? account.name() + "'s" : "account " + accountId + "'s";
                    found.computeIfAbsent(accountId, k -> new ArrayList<>()).add("Shares " + place + " " + value + " only with " + whose + " resources");
                }
            }
        }
        return single(found, OwnerGuess.LOW);
    }

    private OwnerGuess byType() {
        String only = neighbours.onlyAccount();
        if (only == null) {
            return null;
        }
        AccountInfo account = account(only);
        String who = account != null && account.name() != null ? account.name() : "Account " + only;
        return single(Map.of(only, List.of(who + " is the only account with managed resources of this type here")), OwnerGuess.LOW);
    }

    /** The guess when exactly one account was named; none when no account or several were. */
    private OwnerGuess single(Map<String, List<String>> found, String confidence) {
        if (found.size() != 1) {
            return null;
        }
        Map.Entry<String, List<String>> only = found.entrySet().iterator().next();
        AccountInfo account = account(only.getKey());
        return new OwnerGuess(only.getKey(), account == null ? null : account.name(), account == null ? null : account.team(), confidence,
                List.copyOf(new LinkedHashSet<>(only.getValue())));
    }

    private AccountInfo account(String accountId) {
        for (AccountInfo account : accounts) {
            if (account.accountId().equals(accountId)) {
                return account;
            }
        }
        return null;
    }

    private static boolean sameName(String text, String name) {
        return name != null && name.strip().length() >= SHORTEST_NAME && words(text).equals(words(name));
    }

    /** Whether {@code inner} occurs in {@code outer} as consecutive words. */
    private static boolean holds(List<String> outer, List<String> inner) {
        if (inner.isEmpty() || inner.size() > outer.size()) {
            return false;
        }
        for (int i = 0; i + inner.size() <= outer.size(); i++) {
            if (outer.subList(i, i + inner.size()).equals(inner)) {
                return true;
            }
        }
        return false;
    }

    /** A name as lower-case words: "Data-ETL_1043" is [data, etl, 1043]. */
    static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        for (String word : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }

    private static String squash(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Where the managed resources are: the accounts with managed resources of the type, and for
     * each place ({@link #PLACES}) and value, the accounts with a managed resource there. A
     * discovery run fills it from the managed objects it pages past, so the low guesses cost no
     * extra read.
     */
    public static final class Neighbours {

        /** Past this many accounts at one place the place says nothing, so no more are kept. */
        private static final int MOST_KEPT = 2;

        private final Set<String> accounts = new LinkedHashSet<>();
        private final Map<String, Map<String, Set<String>>> byPlace = new HashMap<>();

        public synchronized void add(String accountId, JsonNode attributes) {
            if (accountId == null) {
                return;
            }
            if (accounts.size() < MOST_KEPT) {
                accounts.add(accountId);
            }
            if (attributes == null || !attributes.isObject()) {
                return;
            }
            for (String place : PLACES) {
                String value = text(attributes.get(place));
                if (value != null) {
                    Set<String> there = byPlace.computeIfAbsent(place, k -> new HashMap<>()).computeIfAbsent(value, k -> new LinkedHashSet<>());
                    if (there.size() < MOST_KEPT) {
                        there.add(accountId);
                    }
                }
            }
        }

        /** The one account with managed resources of the type; null when none has or several have. */
        synchronized String onlyAccount() {
            return accounts.size() == 1 ? accounts.iterator().next() : null;
        }

        synchronized Set<String> accountsAt(String place, String value) {
            return Set.copyOf(byPlace.getOrDefault(place, Map.of()).getOrDefault(value, Set.of()));
        }

        static String text(JsonNode value) {
            return value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank() ? null : value.asText().strip();
        }
    }
}
