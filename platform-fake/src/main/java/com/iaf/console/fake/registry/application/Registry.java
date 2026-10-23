package com.iaf.console.fake.registry.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Ids;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.RegionDef;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Delegation;
import com.iaf.console.fake.identity.domain.ReadScopes;
import com.iaf.console.fake.registry.domain.Registration;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.fake.runs.domain.JobContext;
import com.iaf.console.fake.runs.domain.StepError;
import com.iaf.console.fake.runs.domain.StepFailure;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.events.EventKeys;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.wfrn.MintedIds;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

/**
 * The resource registry with P-05's rules: collision-safe keys, wfrns bound from the job's context
 * or minted, the {@code (provider, region, providerId)} index, caller-scoped reads (P-18), paging
 * by id, lineage, the backfill and the collision report.
 */
public final class Registry {

    /** The job id of the registration a re-home emits: no job wrote it. */
    private static final String REHOME = "rehome";

    /** The most resources one page holds. */
    public static final int MAX_LIMIT = 500;

    /** Who registers: the job, its workflow and version, the actor, and the console context when there is one. */
    public record Registrar(String jobId, String workflow, String version, String actor, String ownerTeam, JobContext context,
            EventEnvelope.OnBehalfOf onBehalfOf, String traceparent) {

        public ObjectNode origin() {
            ObjectNode out = Json.object();
            out.put("jobId", jobId);
            out.put("workflow", workflow);
            out.put("version", version);
            return out;
        }
    }

    /** What a register step asks for. */
    public record Request(String type, String givenId, String state, JsonNode attributes, List<String> builtFrom) {
    }

    /** What a registration did: the record, whether it was created, retired, and whether it bound the job's wfrn. */
    public record Outcome(ResourceRecord record, boolean created, boolean retired, boolean boundContextWfrn) {
    }

    /** The filters of a list. */
    public record Filter(String type, String owner, String status, String account, String wfrn, String visibility) {

        public static Filter none() {
            return new Filter(null, null, null, null, null, null);
        }
    }

    /** What a re-home did: the record, and whether it moved (false for the same body again). */
    public record Rehomed(ResourceRecord record, boolean moved) {
    }

    /** One page of resources and the cursor of the next, null on the last. */
    public record Page(List<ResourceRecord> items, String next) {
    }

    private final Map<String, ResourceRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byWfrn = new ConcurrentHashMap<>();
    private final Map<String, String> byProviderKey = new ConcurrentHashMap<>();
    private final FakeClock clock;
    private final Ids ids;
    private final ContentStore content;
    private final EventFeed events;

    public Registry(FakeClock clock, Ids ids, ContentStore content, EventFeed events) {
        this.clock = clock;
        this.ids = ids;
        this.content = content;
        this.events = events;
    }

    /**
     * Registers a resource for a job. {@code contextWfrn} is the job's resource wfrn when it names
     * this type and the job has not bound it yet; a new record takes it, else one is minted.
     */
    public synchronized Outcome register(Request request, Registrar by, String contextWfrn) {
        ResourceTypeDef type = content.type(request.type()).orElseThrow(() -> StepFailure.of("register", "resource.unknownType",
                "no resource type " + request.type()));
        String provider = content.providerOf(type).orElse(null);
        JobContext context = by.context();
        String region = context != null && context.region() != null ? context.region() : content.defaultRegion().name();
        String owner = ownerOf(type, by);
        boolean retiring = "retired".equals(request.state());
        ResourceRecord existing = existingFor(request.givenId(), provider, region);
        long ns = clock.nanos();
        Instant at = FakeClock.fromNanos(ns);
        Registration registration = new Registration(by.jobId(), provider, request.type(), owner, at);
        if (existing != null) {
            boolean sameOwner = existing.owner() == null || owner == null || existing.owner().equals(owner);
            boolean sameProvider = existing.provider() == null || provider == null || existing.provider().equals(provider);
            if (!existing.type().equals(request.type()) || !sameProvider || !sameOwner) {
                throw new StepFailure("register", new StepError("resource.idCollision",
                        "id " + request.givenId() + " belongs to " + existing.type() + " " + existing.id() + " of " + existing.owner(), false));
            }
            if (existing.retired() && !retiring) {
                ResourceRecord fresh = create(type, request, by, provider, region, owner, contextWfrn, existing.id(), at, ns);
                fresh.apply(request.state() == null ? "ready" : request.state(), request.attributes(), request.builtFrom(), by.origin(),
                        registration, at, ns);
                emitRegistered(fresh, by, true, at);
                return new Outcome(fresh, true, false, contextWfrn != null && contextWfrn.equals(fresh.wfrn()));
            }
            existing.apply(request.state(), request.attributes(), request.builtFrom(), null, registration, at, ns);
            if (retiring) {
                emitRetired(existing, by, at);
            } else {
                emitRegistered(existing, by, false, at);
            }
            return new Outcome(existing, false, retiring, false);
        }
        ResourceRecord record = create(type, request, by, provider, region, owner, contextWfrn, null, at, ns);
        record.apply(request.state() == null ? (retiring ? "retired" : "ready") : request.state(), request.attributes(), request.builtFrom(),
                by.origin(), registration, at, ns);
        if (retiring) {
            emitRetired(record, by, at);
        } else {
            emitRegistered(record, by, true, at);
        }
        return new Outcome(record, true, retiring, contextWfrn != null && contextWfrn.equals(record.wfrn()));
    }

    private ResourceRecord create(ResourceTypeDef type, Request request, Registrar by, String provider, String region, String owner,
            String contextWfrn, String supersedes, Instant at, long ns) {
        JobContext context = by.context();
        String id;
        String wfrn = null;
        String accountId = null;
        if (context != null && context.accountId() != null) {
            accountId = context.accountId();
            if (contextWfrn != null && !byWfrn.containsKey(contextWfrn)) {
                wfrn = contextWfrn;
                id = Wfrn.parse(contextWfrn).id();
            } else {
                id = mint(type.name());
                wfrn = new Wfrn(partitionOf(region), type.service(), region, accountId, type.name(), id).format();
            }
        } else if (request.givenId() != null && !request.givenId().isBlank() && !byId.containsKey(request.givenId())) {
            id = request.givenId();
        } else {
            id = mint(type.name());
        }
        String providerId = request.givenId() == null || request.givenId().isBlank() ? null : request.givenId();
        ResourceRecord record = new ResourceRecord(id, type.name(), owner, type.visibility(), provider, wfrn, accountId,
                accountId == null ? null : region, providerId, supersedes, at, ns);
        byId.put(id, record);
        if (wfrn != null) {
            byWfrn.put(wfrn, id);
        }
        if (providerId != null) {
            byProviderKey.put(providerKey(provider, region, providerId), id);
        }
        return record;
    }

    private String mint(String type) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String id = MintedIds.mint(type, ids.ulid());
            if (!byId.containsKey(id)) {
                return id;
            }
        }
        throw new IllegalStateException("could not mint an id for " + type);
    }

    private ResourceRecord existingFor(String givenId, String provider, String region) {
        if (givenId == null || givenId.isBlank()) {
            return null;
        }
        ResourceRecord byOwnId = byId.get(givenId);
        if (byOwnId != null) {
            return byOwnId;
        }
        String id = byProviderKey.get(providerKey(provider, region, givenId));
        if (id != null) {
            return byId.get(id);
        }
        // a provider id registered before the region was known, or under another region of the one partition
        return byId.values().stream().filter(r -> givenId.equals(r.providerId())).findFirst().orElse(null);
    }

    private static String providerKey(String provider, String region, String providerId) {
        return (provider == null ? "-" : provider) + "|" + (region == null ? "-" : region) + "|" + providerId;
    }

    private String partitionOf(String region) {
        return content.region(region).map(RegionDef::partition).orElse("wf");
    }

    /** The team a resource belongs to: the workflow's owner team, else the type's. */
    private static String ownerOf(ResourceTypeDef type, Registrar by) {
        return by.ownerTeam() != null ? by.ownerTeam() : type.owner();
    }

    private void emitRegistered(ResourceRecord record, Registrar by, boolean created, Instant at) {
        emitRegistered(record, by, created, at, null);
    }

    /** {@code resource.registered}; {@code previousWfrn} names the wfrn a re-home moved the record from (P-23). */
    private void emitRegistered(ResourceRecord record, Registrar by, boolean created, Instant at, String previousWfrn) {
        ObjectNode data = Json.object();
        data.put("id", record.id());
        data.put("type", record.type());
        data.put("state", record.status());
        data.put("jobId", by.jobId());
        data.put("workflow", by.workflow());
        data.put("by", by.actor());
        data.put("created", created);
        putIf(data, "wfrn", record.wfrn());
        putIf(data, "accountId", record.accountId());
        putIf(data, "region", record.region());
        putIf(data, "providerId", record.providerId());
        putIf(data, "previousWfrn", previousWfrn);
        emit("resource.registered", record, by, data, at);
    }

    private void emitRetired(ResourceRecord record, Registrar by, Instant at) {
        ObjectNode data = Json.object();
        data.put("id", record.id());
        data.put("type", record.type());
        data.put("by", by.actor());
        putIf(data, "wfrn", record.wfrn());
        putIf(data, "accountId", record.accountId());
        putIf(data, "region", record.region());
        data.put("jobId", by.jobId());
        emit("resource.retired", record, by, data, at);
    }

    private void emit(String type, ResourceRecord record, Registrar by, ObjectNode data, Instant at) {
        EventEnvelope.Context context = null;
        if (record.accountId() != null || by.context() != null) {
            JobContext jc = by.context();
            context = new EventEnvelope.Context(record.accountId() != null ? record.accountId() : jc.accountId(),
                    record.region() != null ? record.region() : jc == null ? null : jc.region(),
                    record.wfrn() != null ? record.wfrn() : jc == null ? null : jc.wfrn(), jc == null ? null : jc.consoleRequestId());
        }
        events.emit(type, EventKeys.resource(type, record.id(), at), at, "resource", record.id(), by.actor(), data, context, by.onBehalfOf(),
                by.traceparent());
    }

    private static void putIf(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    // reads

    /** The record an id names: its own id first, then a provider id. */
    public Optional<ResourceRecord> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ResourceRecord own = byId.get(id);
        if (own != null) {
            return Optional.of(own);
        }
        return byId.values().stream().filter(r -> id.equals(r.providerId())).min(Comparator.comparing(ResourceRecord::createdAt));
    }

    public Optional<ResourceRecord> byWfrn(String wfrn) {
        String id = wfrn == null ? null : byWfrn.get(wfrn);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** The record a provider id names in a region, for observe-list's {@code managed} flag. */
    public Optional<ResourceRecord> byProviderId(String provider, String region, String providerId) {
        String id = byProviderKey.get(providerKey(provider, region, providerId));
        if (id != null) {
            return Optional.ofNullable(byId.get(id));
        }
        return byId.values().stream().filter(r -> providerId.equals(r.providerId()) || providerId.equals(r.id())).findFirst();
    }

    /** A record the caller may see, else empty (a 404, never a 403). */
    public Optional<ResourceRecord> visible(String id, Caller caller) {
        return find(id).filter(r -> sees(caller, r));
    }

    public static boolean sees(Caller caller, ResourceRecord record) {
        return ReadScopes.seesResource(caller, record.owner(), record.visibility(), record.accountId());
    }

    /** The records a filter selects that the caller may see, ordered by id. */
    public List<ResourceRecord> list(Filter filter, Caller caller) {
        List<ResourceRecord> out = new ArrayList<>();
        for (ResourceRecord record : byId.values()) {
            if (!sees(caller, record)) {
                continue;
            }
            if (filter.type() != null && !filter.type().equals(record.type())) {
                continue;
            }
            if (filter.owner() != null && !filter.owner().equals(record.owner())) {
                continue;
            }
            if (filter.status() != null && !filter.status().equals(record.status())) {
                continue;
            }
            if (filter.account() != null && !filter.account().equals(record.accountId())) {
                continue;
            }
            if (filter.wfrn() != null && !filter.wfrn().equals(record.wfrn())) {
                continue;
            }
            if (filter.visibility() != null && !filter.visibility().equals(record.visibility())) {
                continue;
            }
            out.add(record);
        }
        out.sort(Comparator.comparing(ResourceRecord::id));
        return out;
    }

    /** One page of a list: {@code limit} records after the cursor, and the cursor of the next page while there is more. */
    public Page page(Filter filter, Caller caller, int limit, String after) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        String afterId = after == null ? null : CursorPaging.decode(after).id();
        List<ResourceRecord> all = list(filter, caller);
        List<ResourceRecord> items = new ArrayList<>();
        for (ResourceRecord record : all) {
            if (afterId != null && record.id().compareTo(afterId) <= 0) {
                continue;
            }
            if (items.size() == limit) {
                return new Page(items, CursorPaging.encode(items.get(limit - 1).id(), items.get(limit - 1).id()));
            }
            items.add(record);
        }
        return new Page(items, null);
    }

    /** The records changed after an instant, ordered by (updatedNs, id), that the caller may see. */
    public List<ResourceRecord> changedAfter(long updatedAfterNs, Caller caller) {
        return byId.values().stream()
                .filter(r -> sees(caller, r) && r.updatedNs() > updatedAfterNs)
                .sorted(Comparator.comparingLong(ResourceRecord::updatedNs).thenComparing(ResourceRecord::id))
                .toList();
    }

    public List<ResourceRecord> all() {
        return byId.values().stream().sorted(Comparator.comparing(ResourceRecord::id)).toList();
    }

    public int size() {
        return byId.size();
    }

    /** The lineage of a record: what it was built from, what was built from it, and the graph of both. */
    public ObjectNode lineage(ResourceRecord record, Caller caller) {
        ObjectNode out = Json.object();
        out.set("resource", record.document());
        Map<String, ResourceRecord> nodes = new LinkedHashMap<>();
        nodes.put(record.id(), record);
        List<ObjectNode> edges = new ArrayList<>();
        var builtFrom = out.putArray("builtFrom");
        for (String parentId : record.builtFrom()) {
            find(parentId).filter(p -> sees(caller, p)).ifPresent(parent -> {
                builtFrom.add(parent.document());
                nodes.put(parent.id(), parent);
                edges.add(edge(parent.id(), record.id(), via(record)));
            });
        }
        var dependents = out.putArray("dependents");
        for (ResourceRecord other : all()) {
            if (other == record || !sees(caller, other)) {
                continue;
            }
            boolean child = other.builtFrom().stream().anyMatch(record::answersTo);
            if (child) {
                dependents.add(other.document());
                nodes.put(other.id(), other);
                edges.add(edge(record.id(), other.id(), via(other)));
            }
        }
        var nodesNode = out.putArray("nodes");
        nodes.values().forEach(n -> nodesNode.add(n.document()));
        var edgesNode = out.putArray("edges");
        edges.forEach(edgesNode::add);
        return out;
    }

    private static String via(ResourceRecord record) {
        ObjectNode origin = record.createdBy();
        return origin == null ? null : origin.path("workflow").asText(null);
    }

    private static ObjectNode edge(String from, String to, String via) {
        ObjectNode edge = Json.object();
        edge.put("from", from);
        edge.put("to", to);
        if (via != null) {
            edge.put("via", via);
        }
        return edge;
    }

    /** Mints wfrns for a team's resources that have none (P-05's backfill), emitting {@code resource.registered} for each. */
    public synchronized ObjectNode backfill(String accountId, String region, String owner, Caller caller) {
        ObjectNode out = Json.object();
        var items = out.putArray("items");
        int minted = 0;
        int skipped = 0;
        for (ResourceRecord record : all()) {
            if (owner != null && !owner.equals(record.owner())) {
                continue;
            }
            if (record.wfrn() != null) {
                skipped++;
                continue;
            }
            ResourceTypeDef type = content.type(record.type()).orElse(null);
            if (type == null) {
                skipped++;
                continue;
            }
            String wfrn = new Wfrn(partitionOf(region), type.service(), region, accountId, record.type(), record.id()).format();
            long ns = clock.nanos();
            Instant at = FakeClock.fromNanos(ns);
            if (!record.adopt(wfrn, accountId, region, at, ns)) {
                skipped++;
                continue;
            }
            byWfrn.put(wfrn, record.id());
            minted++;
            ObjectNode item = items.addObject();
            item.put("id", record.id());
            item.put("wfrn", wfrn);
            ObjectNode origin = record.createdBy();
            Registrar by = new Registrar(origin == null ? "backfill" : origin.path("jobId").asText("backfill"),
                    origin == null ? "backfill" : origin.path("workflow").asText("backfill"),
                    origin == null ? "0" : origin.path("version").asText("0"), caller.actor(), record.owner(),
                    new JobContext(accountId, region, null, null, Map.of()), null, null);
            emitRegistered(record, by, false, at);
        }
        out.put("minted", minted);
        out.put("skipped", skipped);
        return out;
    }

    /**
     * Moves a record from {@code from} to another account under a new wfrn (P-23); a record moves as
     * often as the console moves it, each old wfrn joining {@code previousWfrns}. A record already at
     * {@code wfrn} changes nothing. The wfrn's account segment must be {@code accountId}, its type and
     * region the record's. 409 {@code resource.rehomed} when the record is at neither {@code from} nor
     * {@code wfrn}, {@code wfrn.taken} when another record holds the wfrn, {@code resource.busy} while
     * {@code busy} says a running job holds it.
     */
    public synchronized Rehomed rehome(String id, String from, String wfrn, String accountId, Caller caller, Predicate<ResourceRecord> busy) {
        Wfrn source = parseWfrn(from);
        Wfrn target = parseWfrn(wfrn);
        if (accountId == null || !AccountScope.isAccountId(accountId)) {
            throw ApiException.badRequest("accountId must be a 12-digit account id");
        }
        ResourceRecord record = find(id).filter(r -> seesForRehome(caller, r)).orElseThrow(() -> ApiException.notFound("resource", id));
        String formatted = target.format();
        if (!target.account().equals(accountId)) {
            throw ApiException.badRequest("the wfrn's account " + target.account() + " is not accountId " + accountId);
        }
        if (formatted.equals(record.wfrn())) {
            return new Rehomed(record, false);
        }
        if (!target.type().equals(record.type())) {
            throw ApiException.badRequest("the wfrn names a " + target.type() + ", the record is a " + record.type());
        }
        if (record.wfrn() == null) {
            throw ApiException.badRequest("resource " + record.id() + " has no wfrn to move from; backfill it first");
        }
        if (!target.region().equals(record.region())) {
            throw ApiException.badRequest("the wfrn's region " + target.region() + " is not the record's " + record.region()
                    + "; a move between regions is a new resource");
        }
        if (!source.format().equals(record.wfrn())) {
            throw new ApiException(409, "resource.rehomed: the record is at " + record.wfrn() + ", not " + source.format(),
                    Map.of("wfrn", record.wfrn()), null);
        }
        String holder = byWfrn.get(formatted);
        if (holder != null && !holder.equals(record.id())) {
            throw ApiException.conflict("wfrn.taken: " + formatted + " names resource " + holder);
        }
        if (busy.test(record)) {
            throw ApiException.conflict("resource.busy: a running job holds " + record.id() + " under " + record.wfrn());
        }
        long ns = clock.nanos();
        Instant at = FakeClock.fromNanos(ns);
        String previous = record.wfrn();
        record.rehome(formatted, accountId, at, ns);
        byWfrn.put(formatted, record.id());
        Delegation delegation = caller.delegation();
        EventEnvelope.OnBehalfOf person = delegation == null ? null : new EventEnvelope.OnBehalfOf(delegation.userId(), delegation.name(),
                delegation.email());
        ObjectNode origin = record.createdBy();
        Registrar by = new Registrar(REHOME, origin == null ? REHOME : origin.path("workflow").asText(REHOME), origin == null ? "0"
                : origin.path("version").asText("0"), caller.actor(), record.owner(), new JobContext(accountId, record.region(), formatted, null,
                        Map.of()), person, null);
        emitRegistered(record, by, false, at, previous);
        return new Rehomed(record, true);
    }

    private static Wfrn parseWfrn(String text) {
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("wfrn.invalid: a wfrn is required");
        }
        try {
            return Wfrn.parse(text);
        } catch (WfrnException e) {
            throw ApiException.badRequest("wfrn.invalid: " + e.getMessage());
        }
    }

    /** A caller who saw the record before it moved still finds it, so the same body again answers 200 rather than 404. */
    private static boolean seesForRehome(Caller caller, ResourceRecord record) {
        if (sees(caller, record)) {
            return true;
        }
        return record.previousWfrns().stream()
                .anyMatch(old -> ReadScopes.seesResource(caller, record.owner(), record.visibility(), Wfrn.parse(old).account()));
    }

    /** The ids whose history shows an overwrite: two jobs through different providers wrote one id. */
    public ObjectNode collisions() {
        ObjectNode out = Json.object();
        var collisions = out.putArray("collisions");
        for (ResourceRecord record : all()) {
            if (!record.overwritten()) {
                continue;
            }
            ObjectNode collision = collisions.addObject();
            collision.put("id", record.id());
            var registrations = collision.putArray("registrations");
            record.registrations().forEach(r -> registrations.add(r.document()));
        }
        return out;
    }

    /** The owner teams that have records, for tests and the seed. */
    public Set<String> owners() {
        Set<String> owners = new LinkedHashSet<>();
        for (ResourceRecord record : all()) {
            if (record.owner() != null) {
                owners.add(record.owner());
            }
        }
        return owners;
    }
}
