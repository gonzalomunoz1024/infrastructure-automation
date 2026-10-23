package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One document of the index: a console resource as search sees it. {@code version} is the
 * resource's state version, which the index writes with external versioning, so an older write
 * never overtakes a newer one; {@code fields} are the values of the type's searchable fields;
 * {@code deleted} documents are tombstones kept until {@code tombstoneUntil}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexedResource(
        String wfrn,
        String accountId,
        String region,
        String type,
        String service,
        String name,
        ResourceState state,
        long version,
        Map<String, String> tags,
        Map<String, Object> fields,
        boolean drift,
        String driftSeverity,
        String driftFindingId,
        String lastJobId,
        String workflow,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Instant tombstoneUntil) {

    public IndexedResource {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        service = service == null || service.isBlank() ? ResourceTypeHints.DEFAULT_SERVICE : service;
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(tags));
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(fields));
        if (version < 0) {
            throw new IllegalArgumentException("a version is not negative");
        }
    }

    /** The id segment of the wfrn ({@code vm-01jbx...}), what people type to find one resource. */
    @JsonIgnore
    public String id() {
        int slash = wfrn.indexOf('/');
        return slash < 0 ? wfrn : wfrn.substring(slash + 1);
    }

    @JsonIgnore
    public boolean deleted() {
        return state == ResourceState.DELETED;
    }

    /** The tags as {@code name=value} strings, what the filters match on. */
    @JsonIgnore
    public List<String> tagsFlat() {
        List<String> flat = new ArrayList<>(tags.size());
        tags.forEach((k, v) -> flat.add(TagRules.flat(k, v)));
        return flat;
    }

    /** Every word search may match beyond the name: the id, the tags and the searchable values. */
    @JsonIgnore
    public String text() {
        StringBuilder sb = new StringBuilder();
        if (name != null) {
            sb.append(name).append(' ');
        }
        sb.append(id()).append(' ');
        tags.forEach((k, v) -> sb.append(k).append(' ').append(v).append(' '));
        fields.forEach((k, v) -> {
            if (v != null) {
                sb.append(v).append(' ');
            }
        });
        return sb.toString().trim();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A mutable copy, for the fold; documents stay immutable outside the domain. */
    public static final class Builder {
        String wfrn;
        String accountId;
        String region;
        String type;
        String service;
        String name;
        ResourceState state;
        long version;
        Map<String, String> tags = new TreeMap<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        boolean drift;
        String driftSeverity;
        String driftFindingId;
        String lastJobId;
        String workflow;
        Instant createdAt;
        Instant updatedAt;
        Instant deletedAt;
        Instant tombstoneUntil;

        Builder() {
        }

        Builder(IndexedResource r) {
            wfrn = r.wfrn;
            accountId = r.accountId;
            region = r.region;
            type = r.type;
            service = r.service;
            name = r.name;
            state = r.state;
            version = r.version;
            tags = new TreeMap<>(r.tags);
            fields = new LinkedHashMap<>(r.fields);
            drift = r.drift;
            driftSeverity = r.driftSeverity;
            driftFindingId = r.driftFindingId;
            lastJobId = r.lastJobId;
            workflow = r.workflow;
            createdAt = r.createdAt;
            updatedAt = r.updatedAt;
            deletedAt = r.deletedAt;
            tombstoneUntil = r.tombstoneUntil;
        }

        public Builder wfrn(String v) {
            wfrn = v;
            return this;
        }

        public Builder accountId(String v) {
            accountId = v;
            return this;
        }

        public Builder region(String v) {
            region = v;
            return this;
        }

        public Builder type(String v) {
            type = v;
            return this;
        }

        public Builder service(String v) {
            service = v;
            return this;
        }

        public Builder name(String v) {
            name = v;
            return this;
        }

        public Builder state(ResourceState v) {
            state = v;
            return this;
        }

        public Builder version(long v) {
            version = v;
            return this;
        }

        public Builder tags(Map<String, String> v) {
            tags = v == null ? new TreeMap<>() : new TreeMap<>(v);
            return this;
        }

        public Builder tag(String name, String value) {
            tags.put(name, value);
            return this;
        }

        public Builder fields(Map<String, Object> v) {
            fields = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }

        public Builder field(String name, Object value) {
            fields.put(name, value);
            return this;
        }

        public Builder drift(boolean v) {
            drift = v;
            return this;
        }

        public Builder driftSeverity(String v) {
            driftSeverity = v;
            return this;
        }

        public Builder driftFindingId(String v) {
            driftFindingId = v;
            return this;
        }

        public Builder lastJobId(String v) {
            lastJobId = v;
            return this;
        }

        public Builder workflow(String v) {
            workflow = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            createdAt = v;
            return this;
        }

        public Builder updatedAt(Instant v) {
            updatedAt = v;
            return this;
        }

        public Builder deletedAt(Instant v) {
            deletedAt = v;
            return this;
        }

        public Builder tombstoneUntil(Instant v) {
            tombstoneUntil = v;
            return this;
        }

        public IndexedResource build() {
            return new IndexedResource(wfrn, accountId, region, type, service, name, state, version, tags, fields, drift, driftSeverity,
                    driftFindingId, lastJobId, workflow, createdAt, updatedAt, deletedAt, tombstoneUntil);
        }
    }
}
