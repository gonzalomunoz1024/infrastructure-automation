package com.iaf.console.state.inventory.application;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Json;

import reactor.core.publisher.Mono;

/**
 * Reads a resource from the platform's registry: an event says that something changed, the
 * registry says what. By wfrn first, by registry id when the name is not bound yet.
 */
public final class PlatformResources {

    private final PlatformPort platform;

    public PlatformResources(PlatformPort platform) {
        this.platform = platform;
    }

    /** The registry's record of a resource; empty when the platform has none. */
    public Mono<Resource> fetch(String wfrn, String registryId) {
        Mono<Resource> byWfrn = wfrn == null ? Mono.empty() : platform.getResourceByWfrn(wfrn).onErrorResume(PlatformResources::notFound, e -> Mono.empty());
        Mono<Resource> byId = registryId == null ? Mono.empty() : platform.getResource(registryId).onErrorResume(PlatformResources::notFound, e -> Mono.empty());
        return byWfrn.switchIfEmpty(byId);
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
