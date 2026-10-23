package com.iaf.console.bff.resources.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.iaf.console.bff.jobs.domain.JobView;

/**
 * A resource as the console shows it: the contract's {@code ConsoleResource} in a list and
 * {@code ConsoleResourceDetail} on its page (the finding, recent jobs, lineage neighbours and the
 * verbs the user may run, from the account's policies).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceView(
        String wfrn,
        String type,
        String service,
        String name,
        String region,
        String state,
        Long version,
        JsonNode attributes,
        JsonNode observed,
        Instant observedAt,
        String observedStatus,
        Boolean drift,
        Map<String, String> tags,
        JsonNode changes,
        JsonNode lastError,
        String lastJobId,
        String requestedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        JobView lastJob,
        FindingView finding,
        List<JobView> jobs,
        List<ResourceRef> builtFrom,
        List<ResourceRef> dependents,
        List<String> can) {

    public static ResourceView summary(StateResource r) {
        return new ResourceView(r.wfrn(), r.type(), r.service(), r.name(), r.region(), r.state(), r.version(), attributes(r), r.observed(),
                r.observedAt(), r.observedStatus(), r.drift() != null, r.tags(), r.changes(), r.lastError(), r.lastJobId(), r.requestedBy(),
                r.createdAt(), r.updatedAt(), r.deletedAt(), null, null, null, null, null, null);
    }

    public static ResourceView detail(StateResource r, JobView lastJob, FindingView finding, List<JobView> jobs, List<ResourceRef> builtFrom,
            List<ResourceRef> dependents, List<String> can) {
        return new ResourceView(r.wfrn(), r.type(), r.service(), r.name(), r.region(), r.state(), r.version(), attributes(r), r.observed(),
                r.observedAt(), r.observedStatus(), r.drift() != null, r.tags(), r.changes(), r.lastError(), r.lastJobId(), r.requestedBy(),
                r.createdAt(), r.updatedAt(), r.deletedAt(), lastJob, finding, jobs == null ? List.of() : List.copyOf(jobs),
                builtFrom == null ? List.of() : List.copyOf(builtFrom), dependents == null ? List.of() : List.copyOf(dependents),
                can == null ? List.of() : List.copyOf(can));
    }

    private static JsonNode attributes(StateResource r) {
        return r.attributes() == null || !r.attributes().isObject() ? JsonNodeFactory.instance.objectNode() : r.attributes();
    }
}
