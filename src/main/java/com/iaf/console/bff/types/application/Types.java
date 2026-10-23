package com.iaf.console.bff.types.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.platform.application.Regions;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.domain.ConsoleAction;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.bff.types.domain.Descriptors;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.ResourceType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The type descriptors of an account and region: the platform's resource types and catalog, read
 * with the account's client (so the platform's visibility rules apply), composed with the P-06
 * defaults, kept for a while per account, and greyed per session with the account's policies
 * ({@code allowed} and {@code denied} on every action). The ETag covers content and decisions.
 */
public class Types {

    private static final Logger log = LoggerFactory.getLogger(Types.class);

    /** The descriptors of one account and region, before any policy is applied, with the catalog entries behind them by workflow. */
    public record Catalogue(List<ConsoleType> types, Map<String, CatalogEntry> entries, String contentHash) {
        public Catalogue {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
        }
    }

    private final PlatformClients clients;
    private final Regions regions;
    private final Access access;
    private final PlatformProperties platform;
    private final TypesProperties properties;
    private final Map<String, Mono<Catalogue>> cache = new ConcurrentHashMap<>();

    public Types(PlatformClients clients, Regions regions, Access access, PlatformProperties platform, TypesProperties properties) {
        this.clients = clients;
        this.regions = regions;
        this.access = access;
        this.platform = platform;
        this.properties = properties;
    }

    /** Every visible type of the session's region, greyed for the session's user. */
    public Mono<List<ConsoleType>> list(Session session) {
        return catalogue(session).flatMap(catalogue -> grey(session, catalogue.types()));
    }

    /** One type by key ({@code <service>/<type>}), greyed for the session's user; 404 when unknown or not in the region. */
    public Mono<ConsoleType> get(Session session, String key) {
        return catalogue(session).flatMap(catalogue -> {
            ConsoleType found = catalogue.types().stream().filter(t -> t.key().equals(key)).findFirst().orElse(null);
            if (found == null) {
                return Mono.error(NotFoundException.of("type", key));
            }
            return grey(session, List.of(found)).map(types -> types.get(0));
        });
    }

    /** The ETag of a list for a session: content plus the decisions the greying was made with. */
    public static String etagOf(List<ConsoleType> types) {
        StringBuilder sb = new StringBuilder();
        for (ConsoleType type : types) {
            sb.append(type.etag()).append(':');
            for (ConsoleAction action : type.actions()) {
                sb.append(action.verb()).append(Boolean.TRUE.equals(action.allowed()) ? '+' : '-');
            }
            sb.append('|');
        }
        return "\"" + Descriptors.sha256(sb.toString()) + "\"";
    }

    /** Drops every cached catalogue; the next read goes to the platform. */
    public void invalidate() {
        cache.clear();
    }

    private Mono<Catalogue> catalogue(Session session) {
        String key = session.accountId() + "/" + session.region();
        return cache.computeIfAbsent(key, k -> load(session).cache(properties.cacheTtl()))
                .onErrorResume(e -> {
                    cache.remove(key);
                    return Mono.error(e);
                });
    }

    private Mono<Catalogue> load(Session session) {
        return clients.forAccount(CurrentSession.scope(session)).flatMap(platform -> Mono.zip(
                platform.listResourceTypes(null).map(c -> c.body() == null ? List.<ResourceType>of() : c.body()),
                platform.listCatalog(null).defaultIfEmpty(List.of()),
                regions.all().map(all -> all.stream().filter(r -> r.name().equals(session.region())).findFirst()
                        .map(r -> r.types()).orElse(List.of()))))
                .map(tuple -> {
                    List<ResourceType> resourceTypes = tuple.getT1();
                    List<CatalogEntry> catalog = tuple.getT2();
                    List<String> inRegion = tuple.getT3();
                    List<ConsoleType> types = new ArrayList<>();
                    for (ResourceType rt : resourceTypes) {
                        if (!inRegion.isEmpty() && !inRegion.contains(rt.name())) {
                            continue;
                        }
                        types.add(Descriptors.describe(rt, catalog));
                    }
                    types.sort((a, b) -> a.key().compareTo(b.key()));
                    StringBuilder hash = new StringBuilder();
                    types.forEach(t -> hash.append(t.etag()).append('|'));
                    Map<String, CatalogEntry> entries = new java.util.HashMap<>();
                    for (CatalogEntry entry : catalog) {
                        if (entry.workflow() != null) {
                            entries.putIfAbsent(entry.workflow(), entry);
                        }
                    }
                    log.debug("descriptors loaded for account {} region {}: {} types", session.accountId(), session.region(), types.size());
                    return new Catalogue(types, entries, Descriptors.sha256(hash.toString()));
                });
    }

    private Mono<List<ConsoleType>> grey(Session session, List<ConsoleType> types) {
        List<AccessCheck> checks = new ArrayList<>();
        for (ConsoleType type : types) {
            String pattern = AccessCheck.typePattern(platform.partition(), type.service(), session.region(), session.accountId(), type.type());
            for (ConsoleAction action : type.actions()) {
                checks.add(new AccessCheck(AccessCheck.action(type.service(), action.verb()), pattern));
            }
        }
        if (checks.isEmpty()) {
            return Mono.just(types);
        }
        return access.decide(session, checks).map(decisions -> {
            List<ConsoleType> greyed = new ArrayList<>(types.size());
            int i = 0;
            for (ConsoleType type : types) {
                List<ConsoleAction> actions = new ArrayList<>(type.actions().size());
                for (ConsoleAction action : type.actions()) {
                    Decision decision = decisions.get(i++);
                    actions.add(action.allowed(decision.allowed(), decision.denial()));
                }
                greyed.add(type.withActions(actions));
            }
            return greyed;
        });
    }

    /** The actions of a type for a session, by verb, for the command endpoints. */
    public Mono<Map<String, ConsoleAction>> actionsOf(Session session, String key) {
        return get(session, key).map(type -> {
            Map<String, ConsoleAction> byVerb = new java.util.LinkedHashMap<>();
            type.actions().forEach(a -> byVerb.put(a.verb(), a));
            return byVerb;
        });
    }

    /** The type a resource type name belongs to, greyed for the session's user; 404 when unknown or not in the region. */
    public Mono<ConsoleType> byType(Session session, String type) {
        return catalogue(session).flatMap(catalogue -> {
            ConsoleType found = catalogue.types().stream().filter(t -> t.type().equals(type)).findFirst().orElse(null);
            if (found == null) {
                return Mono.error(NotFoundException.of("type", type));
            }
            return grey(session, List.of(found)).map(types -> types.get(0));
        });
    }

    /** The catalog entry of a workflow, as the platform published it; 404 when the catalog has none. */
    public Mono<CatalogEntry> entry(Session session, String workflow) {
        return catalogue(session).flatMap(catalogue -> {
            CatalogEntry entry = workflow == null ? null : catalogue.entries().get(workflow);
            return entry == null ? Mono.error(NotFoundException.of("workflow", String.valueOf(workflow))) : Mono.just(entry);
        });
    }

    public Flux<ConsoleType> all(Session session) {
        return list(session).flatMapMany(Flux::fromIterable);
    }
}
