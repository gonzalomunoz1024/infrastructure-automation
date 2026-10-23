package com.iaf.console.state.inventory.application;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.ResourceIdentity;

import reactor.core.publisher.Mono;

/**
 * Reads a resource from the platform's registry: an event says that something changed, the
 * registry says what. By wfrn first, by registry id when the name is not bound yet.
 */
public final class PlatformResources {

    /** The most moves between accounts a platform record's old name is followed through (as the feed does). */
    private static final int MAX_MOVES = 8;

    private final PlatformPort platform;
    private final ResourceLoader loader;

    public PlatformResources(PlatformPort platform, ResourceLoader loader) {
        this.platform = platform;
        this.loader = loader;
    }

    /**
     * The registry's record of a resource; empty when the platform has none. A record found by
     * registry id counts only when it is not bound to another name or another account: the console
     * reads with its own privileged token, so a registry id recorded on one account's resource must
     * never pull another account's record into it.
     */
    public Mono<Resource> fetch(String wfrn, String registryId) {
        Mono<Resource> byWfrn = wfrn == null ? Mono.empty() : platform.getResourceByWfrn(wfrn).onErrorResume(PlatformResources::notFound, e -> Mono.empty());
        Mono<Resource> byId = registryId == null ? Mono.empty() : platform.getResource(registryId).onErrorResume(PlatformResources::notFound, e -> Mono.empty())
                .filterWhen(record -> belongs(record, wfrn) ? Mono.just(true) : movedTo(record.wfrn(), wfrn, 0));
        return byWfrn.switchIfEmpty(byId);
    }

    /**
     * Whether the console itself moved the resource named {@code from} to {@code to} (hub decision
     * 30): a record the platform has not re-homed yet (P-23) still names the old wfrn, and only the
     * console's own tombstones, following their {@code transferredTo}, can vouch for it.
     */
    private Mono<Boolean> movedTo(String from, String to, int hops) {
        if (from == null || to == null || hops >= MAX_MOVES) {
            return Mono.just(false);
        }
        ResourceIdentity id;
        try {
            id = ResourceIdentity.of(from);
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        return loader.load(AccountScope.of(id.accountId(), id.region(), "console"), from)
                .mapNotNull(com.iaf.console.state.inventory.domain.Resource::transferredTo)
                .flatMap(next -> next.equals(to) ? Mono.just(true) : movedTo(next, to, hops + 1))
                .defaultIfEmpty(false);
    }

    /** Whether a record found by registry id may be the resource named {@code wfrn}. */
    static boolean belongs(Resource record, String wfrn) {
        if (wfrn == null) {
            return record.wfrn() == null;
        }
        if (record.wfrn() != null && !record.wfrn().equals(wfrn)) {
            return false;
        }
        return record.accountId() == null || record.accountId().equals(ResourceIdentity.of(wfrn).accountId());
    }

    /** The registration command a platform record spells, for the job that produced it. */
    public static Commands.Registration registration(Resource record, String jobId, String workflow, String workflowVersion, Commands.Meta meta) {
        String job = jobId != null ? jobId : record.createdBy() == null ? null : record.createdBy().jobId();
        String wf = workflow != null ? workflow : record.createdBy() == null ? null : record.createdBy().workflow();
        String version = workflowVersion != null ? workflowVersion : record.createdBy() == null ? null : record.createdBy().version();
        return new Commands.Registration(job, wf, version, record.id(), record.providerId(), record.name(), attributes(record), meta,
                lineage(record));
    }

    /** The import command a platform record spells. */
    public static Commands.Import imported(Resource record, String source, Commands.Meta meta) {
        return new Commands.Import(record.type(), record.region(), record.wfrn(), record.id(), record.providerId(), record.name(),
                attributes(record), source, meta, lineage(record));
    }

    /** The registry ids a record was built from, as the registry lists them. */
    public static List<String> lineage(Resource record) {
        return record.builtFrom() == null ? List.of() : record.builtFrom().stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    public static ObjectNode attributes(Resource record) {
        Map<String, Object> attributes = record.attributes();
        ObjectNode node = attributes == null ? Json.object() : Json.objectNode(attributes);
        if (record.name() != null && !node.has("name")) {
            node.put("name", record.name());
        }
        return node;
    }

    static boolean notFound(Throwable e) {
        return e instanceof PlatformException p && p.isNotFound();
    }
}
