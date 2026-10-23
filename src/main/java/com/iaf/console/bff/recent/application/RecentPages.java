package com.iaf.console.bff.recent.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.common.errors.CodedConflictException;
import com.iaf.console.bff.recent.domain.Favourite;
import com.iaf.console.bff.recent.domain.Favourites;
import com.iaf.console.bff.recent.domain.ListCheck;
import com.iaf.console.bff.recent.domain.RecentItem;
import com.iaf.console.bff.recent.domain.RecentList;
import com.iaf.console.bff.recent.ports.outbound.FavouritesStore;
import com.iaf.console.bff.recent.ports.outbound.RecentStore;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The person's recent pages and favourite resources in the session's account (decision 32.3). Both
 * are the person's own and kept per account. Every read passes the account's policies again: a
 * page or a favourite whose type the person may no longer list ({@code <service>:list} on the type
 * in its region) is left out, not deleted, and shows again if the access comes back.
 */
public class RecentPages {

    /** {@code {items}}. */
    public record Items<T>(List<T> items) {
    }

    private final RecentStore recent;
    private final FavouritesStore favourites;
    private final Access access;
    private final String partition;
    private final Clock clock;

    public RecentPages(RecentStore recent, FavouritesStore favourites, Access access, String partition, Clock clock) {
        this.recent = recent;
        this.favourites = favourites;
        this.access = access;
        this.partition = partition;
        this.clock = clock;
    }

    /** The session account's 20 latest pages, newest first, those whose type the person may still list. */
    public Mono<Items<RecentItem>> recent(Session session) {
        return recent.list(CurrentSession.scope(session), RecentList.MAX)
                .flatMap(items -> listable(session, items, item -> RecentList.checkOf(item, session.region())))
                .map(Items::new);
    }

    /** A page the web opened, {@code {kind, key, title}}: it moves to the top. */
    public Mono<Void> visit(Session session, JsonNode body) {
        return Mono.defer(() -> {
            requireObject(body, "a visit is {kind, key, title}");
            RecentItem item = RecentList.visitOf(text(body, "kind"), text(body, "key"), text(body, "title"), session.accountId(), clock.instant());
            return recent.visit(CurrentSession.scope(session), item, RecentList.MAX);
        });
    }

    /** The session account's favourite resources, newest first, those whose type the person may still list. */
    public Mono<Items<Favourite>> favourites(Session session) {
        return favourites.list(CurrentSession.scope(session))
                .flatMap(items -> listable(session, items, f -> ListCheck.ofWfrn(Wfrn.parse(f.wfrn()))))
                .map(Items::new);
    }

    /**
     * Puts a favourite, {@code {type, name}} (both optional): 400 for a name that is no wfrn or a
     * type that is not the wfrn's, 404 for a resource of another account, 409
     * {@code favourites.full} for a new one past the hundredth.
     */
    public Mono<Favourite> putFavourite(Session session, String name, JsonNode body) {
        return Mono.defer(() -> {
            if (body != null && !body.isNull() && !body.isObject()) {
                requireObject(body, "a favourite is {type, name}");
            }
            Wfrn wfrn = ofAccount(session, name);
            Favourite favourite = Favourites.of(wfrn, text(body, "type"), text(body, "name"), clock.instant());
            return favourites.put(CurrentSession.scope(session), favourite, Favourites.MAX).flatMap(put -> put.full()
                    ? Mono.error(new CodedConflictException(Favourites.FULL,
                            "you have " + Favourites.MAX + " favourites in this account already; remove one to add another",
                            Map.of("limit", Favourites.MAX)))
                    : Mono.just(put.kept()));
        });
    }

    /** Removes a favourite; removing one that is not there is no error. */
    public Mono<Void> removeFavourite(Session session, String name) {
        return Mono.defer(() -> favourites.remove(CurrentSession.scope(session), ofAccount(session, name).format()).then());
    }

    /** The items whose type the person may list, in order: one decision per type and region. */
    private <T> Mono<List<T>> listable(Session session, List<T> items, Function<T, ListCheck> checkOf) {
        List<ListCheck> perItem = new ArrayList<>(items.size());
        Map<ListCheck, Integer> distinct = new LinkedHashMap<>();
        for (T item : items) {
            ListCheck check = checkOf.apply(item);
            perItem.add(check);
            distinct.putIfAbsent(check, distinct.size());
        }
        if (distinct.isEmpty()) {
            return Mono.just(List.of());
        }
        List<AccessCheck> checks = distinct.keySet().stream().map(c -> new AccessCheck(c.action(), c.resource(partition, session.accountId()))).toList();
        return access.decide(session, checks).map(decisions -> {
            List<T> shown = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (decisions.get(distinct.get(perItem.get(i))).allowed()) {
                    shown.add(items.get(i));
                }
            }
            return List.copyOf(shown);
        });
    }

    /** The wfrn of a path, of the session's account: 400 when it is no wfrn, 404 when it names another account's resource. */
    private Wfrn ofAccount(Session session, String name) {
        Wfrn wfrn = Wfrn.parse(name);
        if (!wfrn.partition().equals(partition) || !wfrn.account().equals(session.accountId())) {
            throw new NotFoundException("no resource " + name + " in account " + session.accountId());
        }
        return wfrn;
    }

    private static void requireObject(JsonNode body, String message) {
        if (body == null || !body.isObject()) {
            throw new InvalidInputException(message, List.of(Problem.error("type", "", message)));
        }
    }

    private static String text(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode value = body.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
