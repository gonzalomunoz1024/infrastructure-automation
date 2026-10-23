package com.iaf.console.bff.search.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.GroupedCounts;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.bff.search.ports.outbound.SearchPort;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Search over the account's resources: the search indexer finds them, in the session's account
 * and region only, and the account's policies decide what the person sees. A hit or a suggestion
 * of a type the person may not list ({@code <service>:list} on the type's pattern) is left out,
 * so a page can hold fewer hits than asked while its cursor still goes on; a search, or the
 * counts, of one named type the person may not list is refused as the list itself is. The
 * indexer's {@code didYouMean} is passed on only when it cannot come from a hit left out.
 */
public class Search {

    static final String LIST_VERB = "list";
    public static final int MAX_QUERY = 256;
    public static final int MAX_SUGGEST_QUERY = 128;
    public static final int MAX_SUGGESTIONS = 20;
    public static final int DEFAULT_SUGGESTIONS = 8;
    /** The longest plain English or query a reading or a grouped count takes (the indexer may refuse a shorter one). */
    public static final int MAX_TEXT = 500;
    /** What a grouped count groups by: a type, service, state, region or account, or the value of one tag. */
    public static final Pattern GROUP_BY = Pattern.compile("^(type|service|state|region|account|tag:[A-Za-z0-9._:/-]{1,128})$");
    /** A tag name, as the state service and the indexer take one. */
    static final Pattern TAG_NAME = Pattern.compile("^[a-z0-9-]{1,32}$");

    private final Types types;
    private final Access access;
    private final PlatformProperties platform;
    private final SearchPort index;

    public Search(Types types, Access access, PlatformProperties platform, SearchPort index) {
        this.types = types;
        this.access = access;
        this.platform = platform;
        this.index = index;
    }

    /** One page of hits for free text, over every type the person may list, or over one type. */
    public Mono<SearchPage> search(Session session, String q, String type, Integer limit, String after) {
        return Mono.defer(() -> {
            String text = text(q, MAX_QUERY, true);
            int size = CursorPaging.pageSize(limit);
            return listable(session, type).flatMap(listable -> listable.isEmpty() ? Mono.just(new SearchPage(List.of(), null, null))
                    : index.search(scope(session), caller(session), new SearchQuery(text, blankToNull(type), null, Map.of(), null, null, size, after))
                            .map(page -> {
                                List<SearchHit> shown = page.items().stream().filter(hit -> hit.type() != null && listable.contains(hit.type())).toList();
                                return new SearchPage(shown, page.next(), didYouMean(text, page.didYouMean(), shown, shown.size() < page.items().size()));
                            }));
        });
    }

    /**
     * The indexer's {@code didYouMean} as the person may see it. It is read from the page's hits,
     * so it passes as it is when every hit is shown; when some were left out, only when each word
     * it changes is in the name or a tag of a hit that is shown, since it could otherwise spell a
     * word of a resource the person may not list.
     */
    static String didYouMean(String q, String suggested, List<SearchHit> shown, boolean leftOut) {
        if (suggested == null || suggested.isBlank()) {
            return null;
        }
        if (!leftOut) {
            return suggested;
        }
        String[] asked = q.strip().split("\\s+");
        String[] offered = suggested.strip().split("\\s+");
        if (asked.length != offered.length) {
            return null;
        }
        for (int i = 0; i < offered.length; i++) {
            String word = offered[i];
            if (!word.equalsIgnoreCase(asked[i]) && shown.stream().noneMatch(hit -> spells(hit, word))) {
                return null;
            }
        }
        return suggested;
    }

    /** Whether the hit's name, or one of its tags' names or values, holds the word. */
    private static boolean spells(SearchHit hit, String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (hit.name() != null && hit.name().toLowerCase(Locale.ROOT).contains(lower)) {
            return true;
        }
        return hit.tags().entrySet().stream().anyMatch(t -> t.getKey().toLowerCase(Locale.ROOT).contains(lower)
                || t.getValue().toLowerCase(Locale.ROOT).contains(lower));
    }

    /**
     * Type-ahead: the indexer's best suggestions that the person may see, at most {@code limit}.
     * A resource, a wfrn or a type counts when its type is listable; a tag term when any type is.
     */
    public Mono<List<Suggestion>> suggest(Session session, String q, Integer limit) {
        return Mono.defer(() -> {
            String text = text(q, MAX_SUGGEST_QUERY, true);
            int size = limit == null ? DEFAULT_SUGGESTIONS : limit;
            if (size < 1 || size > MAX_SUGGESTIONS) {
                throw invalid("range", "/limit", "limit is 1 to " + MAX_SUGGESTIONS);
            }
            return listable(session, null).flatMap(listable -> listable.isEmpty() ? Mono.just(List.<Suggestion>of())
                    : index.suggest(scope(session), caller(session), text, null, MAX_SUGGESTIONS)
                            .map(items -> items.stream().filter(s -> visible(s, listable)).limit(size).toList()));
        });
    }

    /**
     * How many of a type's resources a list's filters match, by lifecycle state and with an open
     * drift finding, as {@code <service>:list} on the type allows.
     */
    public Mono<ResourceCounts> counts(Session session, String type, String q, String state, Map<String, String> tags, Boolean drift) {
        return Mono.defer(() -> {
            String text = text(q, MAX_QUERY, false);
            if (state != null && !state.isBlank() && !ResourceCounts.STATES.contains(state)) {
                throw invalid("enum", "/state", "state is one of: " + String.join(", ", ResourceCounts.STATES));
            }
            return types.byType(session, type).flatMap(t -> access.require(session, AccessCheck.action(t.service(), LIST_VERB), pattern(session, t))
                    .then(Mono.defer(() -> index.counts(scope(session), caller(session),
                            new SearchQuery(text, t.type(), blankToNull(state), tags, drift, null, null, null)))));
        });
    }

    /**
     * Counts over accounts the caller has already decided the person may see counted (the
     * executive home's), asked as the console-internal principal with {@code accountId} repeated,
     * in calls of at most {@value SearchPort#MAX_ACCOUNTS} accounts whose answers are added up
     * here. No policy is asked. {@code filters} takes {@code q}, {@code type}, {@code state},
     * {@code drift} ({@code true} or {@code false}), {@code region} (every region when left out)
     * and {@code tag.<name>} (an empty value is any value); {@code groupBy} is {@code type},
     * {@code service}, {@code state}, {@code region}, {@code account} or {@code tag:<name>}, or
     * {@code null} for no groups. The answer is {@code { total, byState, drift, cappedAt?, groups? }}
     * ({@link GroupedCounts}), the groups largest first and at most 100; no account counts nothing.
     */
    public Mono<JsonNode> countsAcross(Session session, List<String> accountIds, Map<String, String> filters, String groupBy) {
        return Mono.defer(() -> {
            CountsQuery query = filtersOf(filters, groupBy == null ? null : groupBy(groupBy));
            List<String> accounts = accountsOf(accountIds);
            if (accounts.isEmpty()) {
                return Mono.just(GroupedCounts.empty(query.grouped()));
            }
            return across(session, accounts, query);
        });
    }

    /**
     * When the search index last applied an event (the indexer's {@code lastEventAt}), asked as
     * the person in the session's account: what the index's lists and counts are as of. Empty
     * before the index's first event.
     */
    public Mono<Instant> lastEventAt(Session session) {
        return Mono.defer(() -> index.getIndexStatus(scope(session), caller(session))).flatMap(status -> Mono.justOrEmpty(status.lastEventAt()));
    }

    /** The counts of the accounts, as the console-internal principal, in chunks the indexer takes, added up. */
    private Mono<JsonNode> across(Session session, List<String> accounts, CountsQuery query) {
        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < accounts.size(); from += SearchPort.MAX_ACCOUNTS) {
            chunks.add(accounts.subList(from, Math.min(accounts.size(), from + SearchPort.MAX_ACCOUNTS)));
        }
        return Flux.fromIterable(chunks).concatMap(chunk -> index.countAcross(scope(session), List.copyOf(chunk), query)).collectList()
                .map(answers -> GroupedCounts.sum(answers, query.grouped()));
    }

    /** The filters an application caller counts with, by name; a name it does not know is a mistake of the caller's. */
    static CountsQuery filtersOf(Map<String, String> filters, String groupBy) {
        String q = null;
        String type = null;
        String state = null;
        String region = null;
        Boolean drift = null;
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<String, String> filter : (filters == null ? Map.<String, String>of() : filters).entrySet()) {
            String value = filter.getValue();
            if (value == null) {
                continue;
            }
            switch (filter.getKey()) {
                case "q" -> q = text(value, MAX_TEXT, false);
                case "type" -> type = blankToNull(value);
                case "state" -> state = state(value);
                case "region" -> region = blankToNull(value);
                case "drift" -> drift = drift(value);
                default -> {
                    if (!filter.getKey().startsWith("tag.")) {
                        throw new IllegalArgumentException("the counts filters are q, type, state, drift, region and tag.<name>, not " + filter.getKey());
                    }
                    tags.put(tagName(filter.getKey().substring(4)), value);
                }
            }
        }
        return new CountsQuery(q, type, region, state, tags, drift, groupBy);
    }

    /** The accounts named, each once, in order; an id that is no account id is a mistake of the caller's. */
    private static List<String> accountsOf(List<String> accountIds) {
        Set<String> accounts = new LinkedHashSet<>();
        for (String accountId : accountIds == null ? List.<String>of() : accountIds) {
            if (!AccountScope.isAccountId(accountId)) {
                throw new IllegalArgumentException("an account id is exactly 12 digits, was '" + accountId + "'");
            }
            accounts.add(accountId);
        }
        return List.copyOf(accounts);
    }

    /** A grouping the indexer takes, else a 400 at {@code /groupBy}. */
    static String groupBy(String value) {
        String groupBy = value == null ? "" : value.strip();
        if (groupBy.isEmpty()) {
            throw invalid("required", "/groupBy", "groupBy is required");
        }
        if (!GROUP_BY.matcher(groupBy).matches()) {
            throw invalid("pattern", "/groupBy", "groupBy is type, service, state, region, account or tag:<name>");
        }
        return groupBy;
    }

    private static String state(String value) {
        String state = blankToNull(value);
        if (state != null && !ResourceCounts.STATES.contains(state)) {
            throw invalid("enum", "/state", "state is one of: " + String.join(", ", ResourceCounts.STATES));
        }
        return state;
    }

    private static Boolean drift(String value) {
        String drift = value.strip();
        if (drift.isEmpty()) {
            return null;
        }
        if (!drift.equals("true") && !drift.equals("false")) {
            throw invalid("type", "/drift", "drift is true or false");
        }
        return Boolean.valueOf(drift);
    }

    private static String tagName(String name) {
        if (!TAG_NAME.matcher(name).matches()) {
            throw invalid("tag.name", "/tag." + name, "a tag name is 1 to 32 lowercase letters, digits or '-'");
        }
        return name;
    }

    // ---- pieces

    /** The types the person may list: the one named (refused when not), or every type of the catalogue they may list. */
    private Mono<Set<String>> listable(Session session, String type) {
        if (type != null && !type.isBlank()) {
            return types.byType(session, type).flatMap(t -> access.require(session, AccessCheck.action(t.service(), LIST_VERB), pattern(session, t))
                    .thenReturn(Set.of(t.type())));
        }
        return types.list(session).flatMap(all -> {
            List<AccessCheck> checks = new ArrayList<>();
            for (ConsoleType t : all) {
                checks.add(new AccessCheck(AccessCheck.action(t.service(), LIST_VERB), pattern(session, t)));
            }
            return access.decide(session, checks).map(decisions -> {
                Set<String> allowed = new HashSet<>();
                for (int i = 0; i < all.size(); i++) {
                    if (decisions.get(i).allowed()) {
                        allowed.add(all.get(i).type());
                    }
                }
                return Set.copyOf(allowed);
            });
        });
    }

    static boolean visible(Suggestion s, Set<String> listable) {
        if (s == null || s.kind() == null) {
            return false;
        }
        return switch (s.kind()) {
            case Suggestion.TAG -> true;
            case Suggestion.TYPE -> listed(listable, s.type() != null ? s.type() : s.value());
            case Suggestion.RESOURCE, Suggestion.WFRN -> listed(listable, typeOf(s));
            default -> false;
        };
    }

    private static boolean listed(Set<String> listable, String type) {
        return type != null && listable.contains(type);
    }

    private static String typeOf(Suggestion s) {
        if (s.type() != null) {
            return s.type();
        }
        String name = s.wfrn() != null ? s.wfrn() : s.value();
        try {
            return name == null ? null : Wfrn.parse(name).type();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String pattern(Session session, ConsoleType t) {
        return AccessCheck.typePattern(platform.partition(), t.service(), session.region(), session.accountId(), t.type());
    }

    private static String text(String q, int max, boolean required) {
        String text = q == null ? "" : q.strip();
        if (required && text.isEmpty()) {
            throw invalid("required", "/q", "q is required");
        }
        if (text.length() > max) {
            throw invalid("length", "/q", "q is at most " + max + " characters");
        }
        return text.isEmpty() ? null : text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, field, message)));
    }

    private static AccountScope scope(Session session) {
        return CurrentSession.scope(session);
    }

    private static StateCaller caller(Session session) {
        return new StateCaller(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles());
    }
}
