package com.iaf.console.bff.resources.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.resources.domain.BlastRadius;
import com.iaf.console.bff.resources.domain.PlatformStates;
import com.iaf.console.bff.resources.domain.ResourceRef;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.Lineage;
import com.iaf.console.platform.model.Resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The blast radius of resources (WP 4.5): the platform registry's lineage walked breadth first
 * from them to the live resources built from them, up to a depth and at most {@value #CAP}
 * resources, with the console's own service account so that another account's dependents are
 * counted too. Only the session's account's dependents are named. A walk from several resources
 * at once (a batch) counts a dependent they share once.
 */
public class BlastRadii {

    public static final int DEFAULT_DEPTH = 3;
    public static final int MAX_DEPTH = 5;
    static final int CAP = 500;
    private static final int CONCURRENCY = 8;

    private final PlatformClients clients;

    public BlastRadii(PlatformClients clients) {
        this.clients = clients;
    }

    /** A walk from several resources: each one's own direct dependents, and everything they reach together. */
    public record Combined(List<BlastRadius> each, BlastRadius together) {
    }

    /** The radius up to {@code depth} steps; a resource the registry has not bound yet has none. */
    public Mono<BlastRadius> of(Session session, StateResource subject, int depth) {
        return ofAll(session, List.of(subject), depth).map(Combined::together);
    }

    /** What depends on the resource directly: the delete guard's question. */
    public Mono<BlastRadius> direct(Session session, StateResource subject) {
        return of(session, subject, 1);
    }

    /** The walk from several resources at once; {@code each} keeps their order and holds one step each. */
    public Mono<Combined> ofAll(Session session, List<StateResource> subjects, int depth) {
        List<String> roots = subjects.stream().map(StateResource::registryId).filter(Objects::nonNull).distinct().toList();
        Walk walk = new Walk(roots);
        Mono<Void> walked = roots.isEmpty() ? Mono.empty() : clients.internal().flatMap(p -> level(p, walk, roots, 1, depth));
        return walked.then(Mono.fromSupplier(() -> {
            List<BlastRadius> each = new ArrayList<>();
            for (StateResource subject : subjects) {
                each.add(subject.registryId() == null ? BlastRadius.none(ResourceRef.of(subject), 1)
                        : walk.directRadius(session.accountId(), subject));
            }
            ResourceRef only = subjects.size() == 1 ? ResourceRef.of(subjects.get(0)) : null;
            return new Combined(each, walk.radius(session.accountId(), only, subjects, depth));
        }));
    }

    private Mono<Void> level(PlatformPort platform, Walk walk, List<String> frontier, int depth, int maxDepth) {
        if (frontier.isEmpty() || depth > maxDepth || walk.full()) {
            return Mono.empty();
        }
        return Flux.fromIterable(frontier).flatMap(id -> lineage(platform, id), CONCURRENCY).collectList().flatMap(lineages -> {
            List<String> next = new ArrayList<>();
            for (Lineage lineage : lineages) {
                String from = lineage.resource() == null ? null : lineage.resource().id();
                if (from == null || lineage.dependents() == null) {
                    continue;
                }
                for (Resource dependent : lineage.dependents()) {
                    if (dependent.id() == null || !PlatformStates.live(dependent)) {
                        continue;
                    }
                    walk.edge(from, dependent.id(), via(lineage, from, dependent.id()));
                    if (depth == 1) {
                        walk.directly(from, dependent.id());
                    }
                    if (walk.add(dependent, depth)) {
                        next.add(dependent.id());
                    }
                }
            }
            return level(platform, walk, next, depth + 1, maxDepth);
        });
    }

    private static Mono<Lineage> lineage(PlatformPort platform, String id) {
        return platform.getResourceLineage(id).onErrorResume(PlatformException.class, e -> e.isNotFound() ? Mono.empty() : Mono.error(e));
    }

    private static String via(Lineage lineage, String from, String to) {
        if (lineage.edges() == null) {
            return null;
        }
        for (Lineage.Edge edge : lineage.edges()) {
            if ((from.equals(edge.from()) && to.equals(edge.to())) || (from.equals(edge.to()) && to.equals(edge.from()))) {
                return edge.via();
            }
        }
        return null;
    }

    /** The resources found so far, one walk's worth; levels run one after the other, so no locking. */
    private static final class Walk {

        private record Node(Resource resource, int depth) {
        }

        private final Set<String> roots;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, Set<String>> directOf = new LinkedHashMap<>();
        private final Set<List<String>> edges = new LinkedHashSet<>();
        private boolean truncated;

        Walk(List<String> roots) {
            this.roots = new LinkedHashSet<>(roots);
        }

        boolean add(Resource resource, int depth) {
            if (roots.contains(resource.id()) || nodes.containsKey(resource.id())) {
                return false;
            }
            if (full()) {
                truncated = true;
                return false;
            }
            nodes.put(resource.id(), new Node(resource, depth));
            return true;
        }

        void directly(String root, String dependent) {
            if (!roots.contains(dependent)) {
                directOf.computeIfAbsent(root, r -> new LinkedHashSet<>()).add(dependent);
            }
        }

        void edge(String from, String to, String via) {
            edges.add(Arrays.asList(from, to, via));
        }

        boolean full() {
            return nodes.size() >= CAP;
        }

        /** One resource's own direct dependents. */
        BlastRadius directRadius(String accountId, StateResource subject) {
            List<Node> direct = new ArrayList<>();
            for (String id : directOf.getOrDefault(subject.registryId(), Set.of())) {
                Node node = nodes.get(id);
                if (node != null) {
                    direct.add(new Node(node.resource(), 1));
                }
            }
            return build(accountId, ResourceRef.of(subject), direct, Map.of(subject.registryId(), subject.wfrn()), 1);
        }

        /** Everything reached; {@code only} is the subject when the walk had one. */
        BlastRadius radius(String accountId, ResourceRef only, List<StateResource> subjects, int depth) {
            Map<String, String> rootNames = new LinkedHashMap<>();
            for (StateResource s : subjects) {
                if (s.registryId() != null) {
                    rootNames.put(s.registryId(), s.wfrn());
                }
            }
            return build(accountId, only, new ArrayList<>(nodes.values()), rootNames, depth);
        }

        private BlastRadius build(String accountId, ResourceRef subject, List<Node> reached, Map<String, String> rootNames, int depth) {
            Map<String, Long> byAccount = new LinkedHashMap<>();
            byAccount.put(accountId, 0L);
            List<BlastRadius.Dependent> named = new ArrayList<>();
            long direct = 0;
            for (Node node : reached) {
                Resource r = node.resource();
                byAccount.merge(r.accountId(), 1L, Long::sum);
                if (node.depth() == 1) {
                    direct++;
                }
                if (accountId.equals(r.accountId())) {
                    named.add(new BlastRadius.Dependent(r.wfrn(), r.type(), r.name(), PlatformStates.of(r.status()), r.id(), node.depth()));
                }
            }
            named.sort(Comparator.comparingInt(BlastRadius.Dependent::depth).thenComparing(d -> d.name() == null ? "" : d.name()));
            List<BlastRadius.AccountCount> counts = new ArrayList<>();
            byAccount.forEach((account, count) -> {
                boolean current = Objects.equals(account, accountId);
                if (count > 0 || current) {
                    counts.add(new BlastRadius.AccountCount(account, count, current));
                }
            });
            counts.sort(Comparator.comparing((BlastRadius.AccountCount c) -> !c.current()).thenComparing(c -> -c.count()));
            Map<String, String> names = new LinkedHashMap<>(rootNames);
            for (BlastRadius.Dependent d : named) {
                names.put(d.id(), d.wfrn() != null ? d.wfrn() : d.id());
            }
            List<BlastRadius.Edge> shown = new ArrayList<>();
            for (List<String> edge : edges) {
                if (names.containsKey(edge.get(0)) && names.containsKey(edge.get(1))) {
                    shown.add(new BlastRadius.Edge(names.get(edge.get(0)), names.get(edge.get(1)), edge.get(2)));
                }
            }
            return new BlastRadius(subject, reached.size(), direct, counts, named, shown, depth, truncated);
        }
    }
}
