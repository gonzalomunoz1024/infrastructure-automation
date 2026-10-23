package com.iaf.console.bff.search.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
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
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * Search over the account's resources: the search indexer finds them, in the session's account
 * and region only, and the account's policies decide what the person sees. A hit or a suggestion
 * of a type the person may not list ({@code <service>:list} on the type's pattern) is left out,
 * so a page can hold fewer hits than asked while its cursor still goes on; a search, or the
 * counts, of one named type the person may not list is refused as the list itself is.
 */
public class Search {

    static final String LIST_VERB = "list";
    public static final int MAX_QUERY = 256;
    public static final int MAX_SUGGEST_QUERY = 128;
    public static final int MAX_SUGGESTIONS = 20;
    public static final int DEFAULT_SUGGESTIONS = 8;

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
    public Mono<Page<SearchHit>> search(Session session, String q, String type, Integer limit, String after) {
        return Mono.defer(() -> {
            String text = text(q, MAX_QUERY, true);
            int size = CursorPaging.pageSize(limit);
            return listable(session, type).flatMap(listable -> listable.isEmpty() ? Mono.just(new Page<SearchHit>(List.of(), null))
                    : index.search(scope(session), caller(session), new SearchQuery(text, blankToNull(type), null, Map.of(), null, null, size, after))
                            .map(page -> new Page<>(page.items().stream().filter(hit -> hit.type() != null && listable.contains(hit.type())).toList(),
                                    page.next())));
        });
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
        return new StateCaller(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles(), session.rolesUntil());
    }
}
