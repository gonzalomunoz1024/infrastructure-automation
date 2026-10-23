package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A console resource as the console sees it, the contract's {@code ConsoleResource}: the fold of
 * its events. {@code attributes} is the desired state (as last registered or requested),
 * {@code observed} what the provider last reported, {@code drift} the open finding. A deleted
 * resource is a tombstone: its {@code state} is {@code deleted} and {@code tombstoneUntil} says how
 * long the name stays reserved.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Resource(
        String wfrn,
        String accountId,
        String region,
        String type,
        String service,
        String name,
        ResourceState state,
        long version,
        String registryId,
        String providerId,
        ObjectNode attributes,
        ObjectNode changes,
        ObjectNode observed,
        String observedStatus,
        Instant observedAt,
        Drift drift,
        Map<String, String> tags,
        String requestedBy,
        String workflow,
        String lastJobId,
        LastError lastError,
        String source,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Instant tombstoneUntil) {

    public Resource {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
    }

    @JsonIgnore
    public boolean live() {
        return state.live();
    }

    @JsonIgnore
    public boolean deleted() {
        return state == ResourceState.DELETED;
    }

    /** The resource's version as an HTTP entity tag. */
    @JsonIgnore
    public String etag() {
        return "\"" + version + "\"";
    }

    /** The tombstone this resource is, when it is deleted. */
    public Tombstone tombstone() {
        return new Tombstone("resource.deleted", wfrn, deletedAt, tombstoneUntil, version);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** A mutable copy, for the fold; records stay immutable outside the domain. */
    public static final class Builder {
        String wfrn;
        String accountId;
        String region;
        String type;
        String service;
        String name;
        ResourceState state;
        long version;
        String registryId;
        String providerId;
        ObjectNode attributes;
        ObjectNode changes;
        ObjectNode observed;
        String observedStatus;
        Instant observedAt;
        Drift drift;
        Map<String, String> tags = new TreeMap<>();
        String requestedBy;
        String workflow;
        String lastJobId;
        LastError lastError;
        String source;
        Instant createdAt;
        Instant updatedAt;
        Instant deletedAt;
        Instant tombstoneUntil;

        Builder() {
        }

        Builder(Resource r) {
            wfrn = r.wfrn;
            accountId = r.accountId;
            region = r.region;
            type = r.type;
            service = r.service;
            name = r.name;
            state = r.state;
            version = r.version;
            registryId = r.registryId;
            providerId = r.providerId;
            attributes = r.attributes == null ? null : r.attributes.deepCopy();
            changes = r.changes == null ? null : r.changes.deepCopy();
            observed = r.observed == null ? null : r.observed.deepCopy();
            observedStatus = r.observedStatus;
            observedAt = r.observedAt;
            drift = r.drift;
            tags = new TreeMap<>(r.tags);
            requestedBy = r.requestedBy;
            workflow = r.workflow;
            lastJobId = r.lastJobId;
            lastError = r.lastError;
            source = r.source;
            createdAt = r.createdAt;
            updatedAt = r.updatedAt;
            deletedAt = r.deletedAt;
            tombstoneUntil = r.tombstoneUntil;
        }

        public Resource build() {
            return new Resource(wfrn, accountId, region, type, service, name, state, version, registryId, providerId, attributes, changes,
                    observed, observedStatus, observedAt, drift, tags, requestedBy, workflow, lastJobId, lastError, source, createdAt, updatedAt,
                    deletedAt, tombstoneUntil);
        }
    }
}
