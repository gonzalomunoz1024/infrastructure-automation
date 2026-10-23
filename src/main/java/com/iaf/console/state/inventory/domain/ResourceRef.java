package com.iaf.console.state.inventory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A resource as the relationship reads show it: enough to draw a node and colour it by state or
 * drift, and to link to the resource's page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceRef(String wfrn, String type, String service, String name, ResourceState state, String accountId, String region,
        DriftRef drift, String observedStatus) {

    /** The open finding, without its fields. */
    public record DriftRef(String findingId, String severity) {
    }

    public static ResourceRef of(Resource r) {
        DriftRef drift = r.drift() == null ? null : new DriftRef(r.drift().findingId(), r.drift().severity());
        return new ResourceRef(r.wfrn(), r.type(), r.service(), r.name(), r.state(), r.accountId(), r.region(), drift, r.observedStatus());
    }
}
