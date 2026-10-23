package com.iaf.console.fake.registry.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A resource of the registry with P-05's fields: the id every caller uses, the wfrn when a console
 * job registered it, the account and region, the provider's own id, and the history of its
 * registrations. Mutated by jobs and read by requests, so every access is synchronized.
 */
public final class ResourceRecord {

    private final String id;
    private final String type;
    private final String owner;
    private final String visibility;
    private final String provider;
    private final String wfrn;
    private final String accountIdAtBirth;
    private final String regionAtBirth;
    private final String supersedes;
    private final Instant createdAt;
    private String status;
    private ObjectNode attributes;
    private ObjectNode createdBy;
    private final Set<String> builtFrom = new LinkedHashSet<>();
    private Instant updatedAt;
    private long updatedNs;
    private String providerId;
    private String accountId;
    private String region;
    private String minted;
    private final List<Registration> registrations = new ArrayList<>();
    private final List<String> previousWfrns = new ArrayList<>();

    public ResourceRecord(String id, String type, String owner, String visibility, String provider, String wfrn, String accountId, String region,
            String providerId, String supersedes, Instant createdAt, long createdNs) {
        this.id = id;
        this.type = type;
        this.owner = owner;
        this.visibility = visibility == null ? "team" : visibility;
        this.provider = provider;
        this.wfrn = wfrn;
        this.accountIdAtBirth = accountId;
        this.regionAtBirth = region;
        this.accountId = accountId;
        this.region = region;
        this.providerId = providerId;
        this.supersedes = supersedes;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.updatedNs = createdNs;
        this.status = "ready";
        this.attributes = JsonNodeFactory.instance.objectNode();
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String owner() {
        return owner;
    }

    public String visibility() {
        return visibility;
    }

    public String provider() {
        return provider;
    }

    /** The current wfrn: the one given at birth, or the one a backfill minted or a re-home gave later. */
    public synchronized String wfrn() {
        return minted != null ? minted : wfrn;
    }

    public synchronized String accountId() {
        return accountId;
    }

    public synchronized String region() {
        return region;
    }

    public synchronized String providerId() {
        return providerId;
    }

    public String supersedes() {
        return supersedes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Instant updatedAt() {
        return updatedAt;
    }

    public synchronized long updatedNs() {
        return updatedNs;
    }

    public synchronized String status() {
        return status;
    }

    public synchronized boolean retired() {
        return "retired".equals(status);
    }

    public synchronized ObjectNode attributes() {
        return attributes.deepCopy();
    }

    public synchronized String name() {
        JsonNode name = attributes.get("name");
        return name != null && name.isTextual() && !name.asText().isBlank() ? name.asText() : id;
    }

    /** The environment the resource lives in, when its attributes say so. */
    public synchronized String environment() {
        JsonNode env = attributes.get("environment");
        if (env == null || !env.isTextual()) {
            env = attributes.get("stage");
        }
        return env != null && env.isTextual() ? env.asText() : null;
    }

    public synchronized ObjectNode createdBy() {
        return createdBy == null ? null : createdBy.deepCopy();
    }

    public synchronized List<String> builtFrom() {
        return List.copyOf(builtFrom);
    }

    public synchronized List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    /** Whether this record answers to an id: its own, or the provider's. */
    public synchronized boolean answersTo(String someId) {
        return id.equals(someId) || (providerId != null && providerId.equals(someId));
    }

    /** Applies a registration: the state when given, the attributes merged, the lineage extended. */
    public synchronized void apply(String state, JsonNode newAttributes, List<String> parents, ObjectNode by, Registration registration, Instant at,
            long nowNs) {
        if (state != null && !state.isBlank()) {
            this.status = state;
        }
        if (newAttributes != null && newAttributes.isObject()) {
            for (Map.Entry<String, JsonNode> field : newAttributes.properties()) {
                if (field.getValue() == null || field.getValue().isNull() || field.getValue().isMissingNode()) {
                    continue;
                }
                attributes.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        if (parents != null) {
            for (String parent : parents) {
                if (parent != null && !parent.isBlank() && !parent.equals(id)) {
                    builtFrom.add(parent);
                }
            }
        }
        if (this.createdBy == null && by != null) {
            this.createdBy = by.deepCopy();
        }
        if (registration != null) {
            registrations.add(registration);
        }
        this.updatedAt = at;
        this.updatedNs = nowNs;
    }

    /** Sets the status outside a registration (the demo seeds a deprecated image). */
    public synchronized void status(String newStatus, Instant at, long nowNs) {
        this.status = newStatus;
        this.updatedAt = at;
        this.updatedNs = nowNs;
    }

    /** Gives a resource registered without a console context a wfrn (P-05's backfill); false when it has one. */
    public synchronized boolean adopt(String newWfrn, String newAccountId, String newRegion, Instant at, long nowNs) {
        if (wfrn() != null) {
            return false;
        }
        this.minted = newWfrn;
        this.accountId = newAccountId;
        this.region = newRegion;
        this.updatedAt = at;
        this.updatedNs = nowNs;
        return true;
    }

    /** The wfrns the record had before it moved to another account (P-23), oldest first. */
    public synchronized List<String> previousWfrns() {
        return List.copyOf(previousWfrns);
    }

    /** Moves the record to another account under a new wfrn (P-23); the current wfrn joins {@link #previousWfrns()}. */
    public synchronized void rehome(String newWfrn, String newAccountId, Instant at, long nowNs) {
        previousWfrns.add(wfrn());
        this.minted = newWfrn;
        this.accountId = newAccountId;
        this.updatedAt = at;
        this.updatedNs = nowNs;
    }

    /** Whether the registrations show an overwrite: two jobs through different providers wrote this id. */
    public synchronized boolean overwritten() {
        for (Registration a : registrations) {
            for (Registration b : registrations) {
                if (!a.jobId().equals(b.jobId()) && a.provider() != null && b.provider() != null && !a.provider().equals(b.provider())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The Resource document. */
    public synchronized ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("type", type);
        out.put("name", name());
        out.put("status", status);
        out.set("attributes", attributes.deepCopy());
        if (owner != null) {
            out.put("owner", owner);
        }
        out.put("visibility", visibility);
        if (createdBy != null) {
            out.set("createdBy", createdBy.deepCopy());
        }
        var parents = out.putArray("builtFrom");
        builtFrom.forEach(parents::add);
        out.put("createdAt", createdAt.toString());
        out.put("updatedAt", updatedAt.toString());
        String name = wfrn();
        if (name != null) {
            out.put("wfrn", name);
        }
        if (accountId != null) {
            out.put("accountId", accountId);
        }
        if (region != null) {
            out.put("region", region);
        }
        if (providerId != null) {
            out.put("providerId", providerId);
        }
        if (supersedes != null) {
            out.put("supersedes", supersedes);
        }
        if (!previousWfrns.isEmpty()) {
            var previous = out.putArray("previousWfrns");
            previousWfrns.forEach(previous::add);
        }
        return out;
    }

    /** The account and region the record was born with, for tests of the birth rules. */
    public String accountIdAtBirth() {
        return accountIdAtBirth;
    }

    public String regionAtBirth() {
        return regionAtBirth;
    }
}
