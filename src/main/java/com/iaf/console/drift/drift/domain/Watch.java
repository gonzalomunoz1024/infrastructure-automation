package com.iaf.console.drift.drift.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One managed resource as the drift service watches it: the desired state the state service
 * holds (so a scan compares a page of provider objects without a read per resource), where its
 * truth is read, its tier and when it is next due, what was last observed, and the fields a
 * decision silenced. {@code rev} is the stored revision: every write is conditional on it, so a
 * scan holding an older copy never undoes an accept, an ignore or a newer desired state.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Watch(
        String wfrn,
        String accountId,
        String region,
        String type,
        String service,
        String environment,
        String name,
        String registryId,
        String providerId,
        String state,
        long desiredVersion,
        ObjectNode desired,
        Map<String, String> tags,
        Instant resourceUpdatedAt,
        int shard,
        Tier tier,
        Instant nextCheckAt,
        Instant lastCheckedAt,
        Instant lastObservationAt,
        String lastObservedDigest,
        String openFindingId,
        Instant lastDriftAt,
        List<IgnoredField> ignored,
        String source,
        long rev) {

    /** The states during which a change is in flight: a read then says nothing about drift. */
    public static final Set<String> IN_FLIGHT = Set.of("pending", "updating", "deleting");

    public Watch {
        Objects.requireNonNull(wfrn, "wfrn");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
        desired = desired == null ? Json.object() : desired;
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
        tier = tier == null ? Tier.WARM : tier;
        ignored = ignored == null ? List.of() : List.copyOf(ignored);
        state = state == null ? "active" : state;
    }

    public boolean inFlight() {
        return IN_FLIGHT.contains(state);
    }

    public boolean deleted() {
        return "deleted".equals(state);
    }

    /** In production: the environment, or a tag that says so. */
    public boolean production() {
        return "prod".equals(environment) || "production".equals(environment) || "prod".equals(tags.get("environment"))
                || "prod".equals(tags.get("env")) || "production".equals(tags.get("environment"));
    }

    /** The digest of the desired attributes: what a silence is bound to. */
    public String desiredDigest() {
        return Json.sha256(DriftRules.normalize(desired).toString());
    }

    /** The silences that still hold for the desired state as it stands. */
    public List<IgnoredField> activeIgnores(Instant now) {
        String digest = desiredDigest();
        return ignored.stream().filter(i -> i.active(digest, now)).toList();
    }

    /**
     * Imported from discovery ({@code source: discover}): the platform's registry does not know the
     * object, so its truth is read from the provider's discover pages by provider id, never by
     * wfrn or registry id.
     */
    public boolean providerOnly() {
        return "discover".equals(source);
    }

    /** The identity of the provider object, as the observe actions take it. */
    public String providerKey() {
        return providerId != null ? providerId : registryId;
    }

    /** The same watch at another revision: what a store answers after a write. */
    public Watch withRev(long newRev) {
        return new Watch(wfrn, accountId, region, type, service, environment, name, registryId, providerId, state, desiredVersion, desired, tags,
                resourceUpdatedAt, shard, tier, nextCheckAt, lastCheckedAt, lastObservationAt, lastObservedDigest, openFindingId, lastDriftAt, ignored,
                source, newRev);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder(String wfrn, String accountId, String region, String type) {
        Builder b = new Builder();
        b.wfrn = wfrn;
        b.accountId = accountId;
        b.region = region;
        b.type = type;
        return b;
    }

    /** A mutable copy; records stay immutable outside the domain's builders. */
    public static final class Builder {
        private String wfrn;
        private String accountId;
        private String region;
        private String type;
        private String service;
        private String environment;
        private String name;
        private String registryId;
        private String providerId;
        private String state;
        private long desiredVersion;
        private ObjectNode desired;
        private Map<String, String> tags = new TreeMap<>();
        private Instant resourceUpdatedAt;
        private int shard;
        private Tier tier;
        private Instant nextCheckAt;
        private Instant lastCheckedAt;
        private Instant lastObservationAt;
        private String lastObservedDigest;
        private String openFindingId;
        private Instant lastDriftAt;
        private List<IgnoredField> ignored = List.of();
        private String source;
        private long rev;

        Builder() {
        }

        Builder(Watch w) {
            wfrn = w.wfrn;
            accountId = w.accountId;
            region = w.region;
            type = w.type;
            service = w.service;
            environment = w.environment;
            name = w.name;
            registryId = w.registryId;
            providerId = w.providerId;
            state = w.state;
            desiredVersion = w.desiredVersion;
            desired = w.desired.deepCopy();
            tags = new TreeMap<>(w.tags);
            resourceUpdatedAt = w.resourceUpdatedAt;
            shard = w.shard;
            tier = w.tier;
            nextCheckAt = w.nextCheckAt;
            lastCheckedAt = w.lastCheckedAt;
            lastObservationAt = w.lastObservationAt;
            lastObservedDigest = w.lastObservedDigest;
            openFindingId = w.openFindingId;
            lastDriftAt = w.lastDriftAt;
            ignored = w.ignored;
            source = w.source;
            rev = w.rev;
        }

        public Builder service(String v) {
            service = v;
            return this;
        }

        public Builder environment(String v) {
            environment = v;
            return this;
        }

        public Builder name(String v) {
            name = v;
            return this;
        }

        public Builder registryId(String v) {
            registryId = v;
            return this;
        }

        public Builder providerId(String v) {
            providerId = v;
            return this;
        }

        public Builder state(String v) {
            state = v;
            return this;
        }

        public Builder desired(long version, ObjectNode attributes) {
            desiredVersion = version;
            desired = attributes == null ? Json.object() : attributes.deepCopy();
            return this;
        }

        public Builder tags(Map<String, String> v) {
            tags = v == null ? new TreeMap<>() : new TreeMap<>(v);
            return this;
        }

        public Builder resourceUpdatedAt(Instant v) {
            resourceUpdatedAt = v;
            return this;
        }

        public Builder shard(int v) {
            shard = v;
            return this;
        }

        public Builder tier(Tier v) {
            tier = v;
            return this;
        }

        public Builder nextCheckAt(Instant v) {
            nextCheckAt = v;
            return this;
        }

        public Builder lastCheckedAt(Instant v) {
            lastCheckedAt = v;
            return this;
        }

        public Builder observation(Instant at, String digest) {
            lastObservationAt = at;
            lastObservedDigest = digest;
            return this;
        }

        public Builder openFindingId(String v) {
            openFindingId = v;
            return this;
        }

        public Builder lastDriftAt(Instant v) {
            lastDriftAt = v;
            return this;
        }

        public Builder ignored(List<IgnoredField> v) {
            ignored = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder source(String v) {
            source = v;
            return this;
        }

        public Watch build() {
            return new Watch(wfrn, accountId, region, type, service, environment, name, registryId, providerId, state, desiredVersion, desired, tags,
                    resourceUpdatedAt, shard, tier, nextCheckAt, lastCheckedAt, lastObservationAt, lastObservedDigest, openFindingId, lastDriftAt,
                    ignored, source, rev);
        }
    }
}
