package com.iaf.console.state.inventory.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.BlastRadius;
import com.iaf.console.state.inventory.domain.Lineage;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceGraph;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceRef;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The relationship reads over the registry's lineage that every resource carries ({@code builtFrom},
 * registry ids): a resource's lineage, the blast radius of taking resources away, and the resource
 * map. The walks resolve registry ids in every account, so a dependent elsewhere is counted, but
 * a caller is only shown resources of the scopes it reads in (an internal caller sees them all).
 */
public final class Relationships {

    /** The most hops a walk takes in one direction, the platform's own lineage limit. */
    public static final int MAX_DEPTH = 32;
    /** The most resources a lineage answer draws, the platform's own lineage limit. */
    public static final int MAX_NODES = 1000;
    /** The nodes a lineage draws when the caller names no limit. */
    public static final int DEFAULT_NODES = 200;
    /** The most dependents a blast radius counts before it says it was cut short. */
    public static final int MAX_AFFECTED = 10_000;
    /** The most resources one blast radius starts from. */
    public static final int MAX_ROOTS = 100;

    private final ResourceLoader loader;
    private final ResourceProjectionPort projection;

    public Relationships(ResourceLoader loader, ResourceProjectionPort projection) {
        this.loader = loader;
        this.projection = projection;
    }

    // lineage

    /** What a resource was built from and what was built from it, {@code depth} hops each way, at most {@code limit} nodes. */
    public Mono<Lineage> lineage(AccountScope scope, String wfrn, int depth, int limit, boolean seesAll) {
        int hops = bounded(depth, 1, MAX_DEPTH, "depth");
        int nodes = bounded(limit, 1, MAX_NODES, "limit");
        return root(scope, wfrn).flatMap(root -> {
            Walk walk = new Walk(List.of(scope), seesAll, nodes);
            walk.add(root);
            return up(walk, List.of(root), 1, hops)
                    .then(Mono.defer(() -> down(walk, List.of(root), 1, hops)))
                    .then(Mono.fromSupplier(() -> walk.lineage(root)));
        });
    }

    private Mono<Void> up(Walk walk, List<Resource> frontier, int level, int depth) {
        Set<String> ids = new LinkedHashSet<>();
        frontier.forEach(r -> ids.addAll(r.builtFrom()));
        if (ids.isEmpty() || level > depth) {
            return Mono.empty();
        }
        return projection.findByRegistryIds(ids).collectList().flatMap(found -> {
            Map<String, Resource> byId = preferLive(found);
            List<Resource> next = new ArrayList<>();
            for (Resource child : frontier) {
                for (String id : child.builtFrom()) {
                    Resource parent = byId.get(id);
                    if (parent == null) {
                        walk.unmanaged.add(id);
                    } else if (!walk.visible(parent)) {
                        walk.hidden.add(parent.wfrn());
                    } else {
                        walk.edge(parent, child);
                        if (level == 1) {
                            walk.parents.add(parent.wfrn());
                        }
                        if (walk.add(parent)) {
                            next.add(parent);
                        }
                    }
                }
            }
            return up(walk, next, level + 1, depth);
        });
    }

    private Mono<Void> down(Walk walk, List<Resource> frontier, int level, int depth) {
        Map<String, Resource> byId = new HashMap<>();
        frontier.stream().filter(r -> r.registryId() != null).forEach(r -> byId.put(r.registryId(), r));
        if (byId.isEmpty() || level > depth) {
            return Mono.empty();
        }
        int fetch = Math.min(MAX_AFFECTED, walk.room() * 4 + 1);
        return projection.findDependents(byId.keySet(), fetch).collectList().flatMap(children -> {
            if (children.size() >= fetch) {
                walk.truncated = true;
            }
            List<Resource> next = new ArrayList<>();
            for (Resource child : children) {
                if (!walk.visible(child)) {
                    walk.hidden.add(child.wfrn());
                    continue;
                }
                for (String id : child.builtFrom()) {
                    Resource parent = byId.get(id);
                    if (parent != null) {
                        walk.edge(parent, child);
                        if (level == 1) {
                            walk.children.add(child.wfrn());
                        }
                    }
                }
                if (walk.add(child)) {
                    next.add(child);
                }
            }
            return down(walk, next, level + 1, depth);
        });
    }

    // blast radius

    /** Every live resource built from the roots, directly or through others, in every account, {@code depth} hops deep. */
    public Mono<BlastRadius> blastRadius(AccountScope scope, Collection<String> wfrns, int depth, boolean seesAll) {
        int hops = bounded(depth, 1, MAX_DEPTH, "depth");
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(wfrns));
        if (distinct.isEmpty() || distinct.size() > MAX_ROOTS) {
            return Mono.error(new IllegalArgumentException("a blast radius starts from 1 to " + MAX_ROOTS + " resources"));
        }
        return Flux.fromIterable(distinct).concatMap(w -> root(scope, w)).collectList().flatMap(roots -> {
            Tally tally = new Tally(scope, seesAll);
            roots.forEach(r -> tally.seen.add(r.wfrn()));
            return affected(tally, roots, 1, hops).then(Mono.fromSupplier(() -> tally.answer(distinct)));
        });
    }

    private Mono<Void> affected(Tally tally, List<Resource> frontier, int level, int depth) {
        Set<String> ids = new LinkedHashSet<>();
        frontier.stream().filter(r -> r.registryId() != null).forEach(r -> ids.add(r.registryId()));
        if (ids.isEmpty() || level > depth || tally.truncated) {
            return Mono.empty();
        }
        int fetch = MAX_AFFECTED - tally.total + 1;
        return projection.findDependents(ids, fetch).collectList().flatMap(children -> {
            List<Resource> next = new ArrayList<>();
            for (Resource child : children) {
                if (!tally.seen.add(child.wfrn())) {
                    continue;
                }
                if (tally.total == MAX_AFFECTED) {
                    tally.truncated = true;
                    break;
                }
                tally.count(child);
                next.add(child);
            }
            if (!next.isEmpty()) {
                tally.depth = level;
            }
            return affected(tally, next, level + 1, depth);
        });
    }

    // the resource map

    /**
     * The map of one or more accounts: their live resources that match the query (at most
     * {@code limit}), the lineage between them, context nodes for related resources the caller may
     * see outside the filter, and other accounts' resources summed up by account and type.
     */
    public Mono<ResourceGraph> graph(List<AccountScope> scopes, ResourceQuery query, int limit, boolean seesAll) {
        int most = bounded(limit, 1, ResourceGraph.MAX_NODES, "limit");
        Walk map = new Walk(scopes, seesAll, most);
        return Flux.fromIterable(scopes).concatMap(scope -> pages(scope, query, map)).then(Mono.defer(() -> outside(map)));
    }

    private Mono<Void> pages(AccountScope scope, ResourceQuery query, Walk map) {
        int room = map.room();
        if (room <= 0) {
            return Mono.empty();
        }
        ResourceQuery page = query.withSort(ResourceQuery.Sort.NAME_ASC).withLimit(Math.min(ResourceQuery.MAX_LIMIT, room));
        return projection.list(scope, page).flatMap(found -> {
            for (Resource r : found.items()) {
                map.add(r);
            }
            boolean more = found.next() != null;
            if (more && map.room() <= 0) {
                map.truncated = true;
                return Mono.<Void>empty();
            }
            return more ? pages(scope, query.withAfter(CursorPaging.decode(found.next())), map) : Mono.<Void>empty();
        });
    }

    private Mono<ResourceGraph> outside(Walk map) {
        List<Resource> members = new ArrayList<>(map.nodes.values());
        Map<String, Resource> byId = new HashMap<>();
        members.stream().filter(r -> r.registryId() != null).forEach(r -> byId.put(r.registryId(), r));
        Set<String> unresolved = new LinkedHashSet<>();
        for (Resource child : members) {
            for (String id : child.builtFrom()) {
                Resource parent = byId.get(id);
                if (parent != null) {
                    map.edge(parent, child);
                } else {
                    unresolved.add(id);
                }
            }
        }
        Map<String, Integer> external = new TreeMap<>();
        Mono<Void> parents = unresolved.isEmpty() ? Mono.empty() : projection.findByRegistryIds(unresolved).collectList().doOnNext(found -> {
            Map<String, Resource> resolved = preferLive(found);
            Set<String> counted = new LinkedHashSet<>();
            for (String id : unresolved) {
                Resource parent = resolved.get(id);
                if (parent == null) {
                    map.unmanaged.add(id);
                } else if (map.visible(parent)) {
                    if (map.contains(parent) || map.addContext(parent)) {
                        members.stream().filter(m -> m.builtFrom().contains(id)).forEach(m -> map.edge(parent, m));
                    }
                } else if (counted.add(parent.wfrn())) {
                    external.merge(parent.accountId() + "\u0000" + parent.type() + "\u0000builtFrom", 1, Integer::sum);
                }
            }
        }).then();
        Mono<Void> dependents = byId.isEmpty() ? Mono.empty() : projection.findDependents(byId.keySet(), MAX_AFFECTED).collectList().doOnNext(found -> {
            if (found.size() >= MAX_AFFECTED) {
                map.truncated = true;
            }
            for (Resource child : found) {
                if (map.contains(child)) {
                    continue;
                }
                if (map.visible(child)) {
                    if (map.addContext(child)) {
                        child.builtFrom().stream().map(byId::get).filter(p -> p != null).forEach(p -> map.edge(p, child));
                    }
                } else {
                    external.merge(child.accountId() + "\u0000" + child.type() + "\u0000dependent", 1, Integer::sum);
                }
            }
        }).then();
        return parents.then(dependents).then(Mono.fromSupplier(() -> {
            List<ResourceGraph.External> groups = new ArrayList<>();
            external.forEach((key, count) -> {
                String[] parts = key.split("\u0000");
                groups.add(new ResourceGraph.External(parts[0], parts[1], parts[2], count));
            });
            List<ResourceGraph.Node> nodes = map.nodes.values().stream().map(r -> ResourceGraph.Node.of(r, map.context.contains(r.wfrn()))).toList();
            return new ResourceGraph(nodes, List.copyOf(map.edges.values()), groups, List.copyOf(new TreeSet<>(map.unmanaged)), map.truncated);
        }));
    }

    // helpers

    private Mono<Resource> root(AccountScope scope, String wfrn) {
        ResourceIdentity id;
        try {
            id = ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("not a wfrn: " + e.getMessage()));
        }
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        return loader.load(scope, wfrn).switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)));
    }

    /** One resource per registry id: the live one when an id names a live resource and a tombstone. */
    static Map<String, Resource> preferLive(List<Resource> found) {
        Map<String, Resource> byId = new HashMap<>();
        for (Resource r : found) {
            byId.merge(r.registryId(), r, (a, b) -> a.live() ? a : b);
        }
        return byId;
    }

    private static int bounded(int value, int min, int max, String what) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(what + " is " + min + " to " + max);
        }
        return value;
    }

    /** What a lineage walk or a map has gathered so far. */
    private static final class Walk {
        final List<AccountScope> scopes;
        final boolean seesAll;
        final int limit;
        final Map<String, Resource> nodes = new LinkedHashMap<>();
        final Map<String, Lineage.Edge> edges = new LinkedHashMap<>();
        final Set<String> context = new LinkedHashSet<>();
        final Set<String> hidden = new LinkedHashSet<>();
        final Set<String> unmanaged = new LinkedHashSet<>();
        final Set<String> parents = new LinkedHashSet<>();
        final Set<String> children = new LinkedHashSet<>();
        boolean truncated;

        Walk(List<AccountScope> scopes, boolean seesAll, int limit) {
            this.scopes = scopes;
            this.seesAll = seesAll;
            this.limit = limit;
        }

        boolean visible(Resource r) {
            return seesAll || scopes.stream().anyMatch(s -> s.covers(r.accountId(), r.region()));
        }

        boolean contains(Resource r) {
            return nodes.containsKey(r.wfrn());
        }

        int room() {
            return limit - nodes.size();
        }

        /** Adds a node; false when it is there already or there is no room left (which cuts the answer short). */
        boolean add(Resource r) {
            if (nodes.containsKey(r.wfrn())) {
                return false;
            }
            if (nodes.size() >= limit) {
                truncated = true;
                return false;
            }
            nodes.put(r.wfrn(), r);
            return true;
        }

        boolean addContext(Resource r) {
            if (add(r)) {
                context.add(r.wfrn());
                return true;
            }
            return false;
        }

        void edge(Resource from, Resource to) {
            edges.putIfAbsent(from.wfrn() + " " + to.wfrn(), new Lineage.Edge(from.wfrn(), to.wfrn(), to.workflow()));
        }

        Lineage lineage(Resource root) {
            List<ResourceRef> refs = nodes.values().stream().map(ResourceRef::of).toList();
            List<Lineage.Edge> drawn = edges.values().stream().filter(e -> nodes.containsKey(e.from()) && nodes.containsKey(e.to())).toList();
            return new Lineage(ResourceRef.of(root), refsOf(parents), refsOf(children), refs, drawn, List.copyOf(new TreeSet<>(unmanaged)),
                    hidden.size(), truncated);
        }

        private List<ResourceRef> refsOf(Set<String> wfrns) {
            return wfrns.stream().map(nodes::get).filter(r -> r != null).map(ResourceRef::of).toList();
        }
    }

    /** What a blast radius has counted so far. */
    private static final class Tally {
        final AccountScope scope;
        final boolean seesAll;
        final Set<String> seen = new LinkedHashSet<>();
        final Map<String, Integer> accounts = new HashMap<>();
        final Map<String, Integer> types = new HashMap<>();
        final List<ResourceRef> items = new ArrayList<>();
        int total;
        int depth;
        boolean truncated;

        Tally(AccountScope scope, boolean seesAll) {
            this.scope = scope;
            this.seesAll = seesAll;
        }

        void count(Resource r) {
            total++;
            accounts.merge(r.accountId(), 1, Integer::sum);
            types.merge(r.type(), 1, Integer::sum);
            if (items.size() < BlastRadius.ITEMS && (seesAll || scope.covers(r.accountId(), r.region()))) {
                items.add(ResourceRef.of(r));
            }
        }

        BlastRadius answer(List<String> roots) {
            List<BlastRadius.AccountCount> byAccount = accounts.entrySet().stream()
                    .map(e -> new BlastRadius.AccountCount(e.getKey(), e.getValue(), e.getKey().equals(scope.accountId())))
                    .sorted(Comparator.comparing(BlastRadius.AccountCount::yours).reversed()
                            .thenComparing(Comparator.comparingInt(BlastRadius.AccountCount::count).reversed())
                            .thenComparing(BlastRadius.AccountCount::accountId))
                    .toList();
            List<BlastRadius.TypeCount> byType = types.entrySet().stream()
                    .map(e -> new BlastRadius.TypeCount(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingInt(BlastRadius.TypeCount::count).reversed().thenComparing(BlastRadius.TypeCount::type))
                    .toList();
            return new BlastRadius(roots, total, byAccount, byType, items, depth, truncated);
        }
    }
}
