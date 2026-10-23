package com.iaf.console.bff.demo.state.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.iaf.console.bff.live.application.FeedListener;
import com.iaf.console.bff.live.domain.ConsoleEvents;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.resources.domain.Tombstone;
import com.iaf.console.bff.types.domain.Descriptors;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.Resource;
import com.iaf.console.platform.model.ResourceType;

import reactor.core.publisher.Mono;

/**
 * The state service's inventory, in memory, for the demo: minted names, the commands the BFF
 * sends, a log per resource in the console's envelope, tombstones, and the platform's feed applied
 * the way the real service applies it (a registration makes a pending resource active with the
 * registry's attributes, a job's end records the outcome, a retirement is the end). Every event
 * also reaches the live hub, as the Kafka consumer would deliver it.
 */
public class DemoStateStore implements FeedListener {

    public static final String PLATFORM = "platform";
    private static final Logger log = LoggerFactory.getLogger(DemoStateStore.class);
    private static final Duration TOMBSTONE = Duration.ofDays(90);
    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A command's answer: the resource, and whether it was a replay of the idempotency key. */
    public record Answer(StateResource resource, boolean replayed) {
    }

    /** The current record of a wfrn, mutable, turned into the contract's shape on every read. */
    private static final class Row {
        String wfrn;
        String accountId;
        String region;
        String type;
        String service;
        String name;
        String state;
        long version;
        String registryId;
        String providerId;
        ObjectNode attributes;
        ObjectNode observed;
        Instant observedAt;
        String observedStatus;
        StateResource.Drift drift;
        Map<String, String> tags = new LinkedHashMap<>();
        String requestedBy;
        String lastJobId;
        ObjectNode lastError;
        ObjectNode changes;
        String source;
        Instant createdAt;
        Instant updatedAt;
        Instant deletedAt;
        Instant tombstoneUntil;
        final List<EventEnvelope> log = new ArrayList<>();

        StateResource resource() {
            return new StateResource(wfrn, accountId, region, type, service, name, state, version, registryId, providerId, attributes, observed,
                    observedAt, observedStatus, drift, tags, requestedBy, lastJobId, lastError, changes, source, createdAt, updatedAt, deletedAt,
                    tombstoneUntil);
        }

        boolean live() {
            return !StateResource.DELETED.equals(state);
        }
    }

    private final PlatformClients clients;
    private final LiveHub hub;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, Answer> replays = new ConcurrentHashMap<>();
    private final Map<String, String> servicesByType = new ConcurrentHashMap<>();
    private final AtomicLong eventIds = new AtomicLong();

    public DemoStateStore(PlatformClients clients, LiveHub hub, ObjectMapper json, Clock clock) {
        this.clients = clients;
        this.hub = hub;
        this.json = json;
        this.clock = clock;
    }

    // ---- reads

    public synchronized Page<StateResource> list(String accountId, String region, StateQuery query) {
        List<StateResource> all = new ArrayList<>();
        for (Row row : rows.values()) {
            if (!row.accountId.equals(accountId) || (region != null && !row.region.equals(region))) {
                continue;
            }
            if (query.state() == null ? !row.live() : !query.state().equals(row.state)) {
                continue;
            }
            if (query.type() != null && !query.type().equals(row.type)) {
                continue;
            }
            if (query.wfrn() != null && !query.wfrn().equals(row.wfrn)) {
                continue;
            }
            if (query.name() != null && !query.name().isBlank()
                    && (row.name == null || !row.name.toLowerCase(Locale.ROOT).contains(query.name().toLowerCase(Locale.ROOT)))) {
                continue;
            }
            if (query.drift() != null && query.drift() != (row.drift != null)) {
                continue;
            }
            boolean tagsMatch = true;
            for (Map.Entry<String, String> tag : query.tags().entrySet()) {
                if (!tag.getValue().equals(row.tags.get(tag.getKey()))) {
                    tagsMatch = false;
                }
            }
            if (!tagsMatch) {
                continue;
            }
            all.add(row.resource());
        }
        all.sort(comparator(query.sort()));
        int limit = query.limit() == null ? 50 : Math.max(1, Math.min(200, query.limit()));
        int from = 0;
        if (query.after() != null && !query.after().isBlank()) {
            try {
                from = Integer.parseInt(query.after());
            } catch (NumberFormatException e) {
                throw new InvalidInputException("after is not a cursor of this list", List.of(Problem.error("cursor", "/after", "unknown cursor")));
            }
        }
        List<StateResource> page = all.subList(Math.min(from, all.size()), Math.min(from + limit, all.size()));
        String next = from + limit < all.size() ? String.valueOf(from + limit) : null;
        return new Page<>(new ArrayList<>(page), next);
    }

    private static Comparator<StateResource> comparator(String sort) {
        String s = sort == null || sort.isBlank() ? "-updatedAt" : sort;
        boolean descending = s.startsWith("-");
        String field = descending ? s.substring(1) : s;
        Comparator<StateResource> c = switch (field) {
            case "updatedAt" -> Comparator.comparing(StateResource::updatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "createdAt" -> Comparator.comparing(StateResource::createdAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "name" -> Comparator.comparing(StateResource::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> throw new InvalidInputException("unknown sort", List.of(Problem.error("enum", "/sort", "one of: -updatedAt, updatedAt, -createdAt, createdAt, name, -name")));
        };
        c = c.thenComparing(StateResource::wfrn);
        return descending ? c.reversed() : c;
    }

    /** Every record of an account in a region (every region when {@code null}), tombstones too: what the demo search index reads. */
    public synchronized List<StateResource> snapshot(String accountId, String region) {
        List<StateResource> all = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.accountId.equals(accountId) && (region == null || row.region.equals(region))) {
                all.add(row.resource());
            }
        }
        return all;
    }

    /** The resource, or its tombstone, or nothing. */
    public synchronized Optional<Object> get(String accountId, String wfrn) {
        Row row = rows.get(wfrn);
        if (row == null || (accountId != null && !row.accountId.equals(accountId))) {
            return Optional.empty();
        }
        if (!row.live()) {
            return Optional.of(new Tombstone("resource deleted", row.wfrn, row.deletedAt, row.tombstoneUntil, row.version));
        }
        return Optional.of(row.resource());
    }

    public synchronized Optional<Page<JsonNode>> events(String accountId, String wfrn, int limit, long afterVersion) {
        Row row = rows.get(wfrn);
        if (row == null || (accountId != null && !row.accountId.equals(accountId))) {
            return Optional.empty();
        }
        List<JsonNode> items = new ArrayList<>();
        long last = afterVersion;
        for (EventEnvelope event : row.log) {
            long version = event.data() == null ? 0 : event.data().path("version").asLong();
            if (version <= afterVersion) {
                continue;
            }
            if (items.size() >= limit) {
                return Optional.of(new Page<>(items, String.valueOf(last)));
            }
            items.add(json.valueToTree(event));
            last = version;
        }
        return Optional.of(new Page<>(items, null));
    }

    // ---- commands

    /** Mints a name for an account's new resource; the type's service is the platform's resource type's owner. */
    public Mono<Answer> mint(String accountId, String region, JsonNode body, String actor, String key, String requestId) {
        String type = text(body, "type");
        if (type == null) {
            return Mono.error(new InvalidInputException("a mint names its type", List.of(Problem.error("required", "/type", "the resource type"))));
        }
        return serviceOf(type).map(service -> {
            synchronized (this) {
                Answer replay = replay(key);
                if (replay != null) {
                    return replay;
                }
                String chosenRegion = text(body, "region") == null ? region : text(body, "region");
                String name = text(body, "name");
                Row row = new Row();
                row.wfrn = freshName(service, chosenRegion, accountId, type, name);
                row.accountId = accountId;
                row.region = chosenRegion;
                row.type = type;
                row.service = service;
                row.name = name;
                row.state = StateResource.PENDING;
                row.version = 1;
                row.attributes = body.hasNonNull("input") && body.get("input").isObject() ? ((ObjectNode) body.get("input")).deepCopy() : json.createObjectNode();
                row.requestedBy = actor;
                row.createdAt = clock.instant();
                row.updatedAt = row.createdAt;
                rows.put(row.wfrn, row);
                ObjectNode data = json.createObjectNode();
                data.put("workflow", text(body, "workflow"));
                data.set("attributes", row.attributes);
                event(row, "ResourceRequested", actor, null, requestId, data);
                return remember(key, new Answer(row.resource(), false));
            }
        });
    }

    public synchronized Answer requestUpdate(String accountId, String wfrn, JsonNode body, String actor, String key, String requestId) {
        Answer replay = replay(key);
        if (replay != null) {
            return replay;
        }
        Row row = live(accountId, wfrn);
        expectVersion(row, body);
        if (!StateResource.ACTIVE.equals(row.state) && !StateResource.FAILED.equals(row.state)) {
            throw new ConflictException("the resource is " + row.state, Map.of("state", row.state));
        }
        row.state = StateResource.UPDATING;
        row.changes = body.hasNonNull("changes") && body.get("changes").isObject() ? ((ObjectNode) body.get("changes")).deepCopy() : json.createObjectNode();
        row.lastJobId = text(body, "jobId") != null ? text(body, "jobId") : row.lastJobId;
        ObjectNode data = json.createObjectNode();
        data.set("changes", row.changes);
        data.put("workflow", text(body, "workflow"));
        bump(row);
        event(row, "UpdateRequested", actor, text(body, "jobId"), requestId, data);
        return remember(key, new Answer(row.resource(), false));
    }

    public synchronized Answer requestDelete(String accountId, String wfrn, JsonNode body, String actor, String key, String requestId) {
        Answer replay = replay(key);
        if (replay != null) {
            return replay;
        }
        Row row = live(accountId, wfrn);
        expectVersion(row, body);
        if (!StateResource.ACTIVE.equals(row.state) && !StateResource.FAILED.equals(row.state)) {
            throw new ConflictException("the resource is " + row.state, Map.of("state", row.state));
        }
        row.state = StateResource.DELETING;
        row.lastJobId = text(body, "jobId") != null ? text(body, "jobId") : row.lastJobId;
        ObjectNode data = json.createObjectNode();
        data.put("workflow", text(body, "workflow"));
        if (text(body, "reason") != null) {
            data.put("reason", text(body, "reason"));
        }
        bump(row);
        event(row, "DeleteRequested", actor, text(body, "jobId"), requestId, data);
        return remember(key, new Answer(row.resource(), false));
    }

    public synchronized Answer tag(String accountId, String wfrn, JsonNode body, String actor, String key, String requestId) {
        Answer replay = replay(key);
        if (replay != null) {
            return replay;
        }
        Row row = live(accountId, wfrn);
        expectVersion(row, body);
        List<Problem> problems = new ArrayList<>();
        Map<String, String> set = new LinkedHashMap<>();
        body.path("set").properties().forEach(e -> {
            if (!e.getKey().matches("[a-z0-9-]{1,32}")) {
                problems.add(Problem.error("tag.name", "/set/" + e.getKey(), "a tag name is [a-z0-9-]{1,32}"));
            } else if (e.getValue().asText("").length() > 256) {
                problems.add(Problem.error("tag.value", "/set/" + e.getKey(), "a tag value has at most 256 characters"));
            } else {
                set.put(e.getKey(), e.getValue().asText());
            }
        });
        if (!problems.isEmpty()) {
            throw new InvalidInputException("bad tags", problems);
        }
        List<String> removed = new ArrayList<>();
        body.path("removed").forEach(n -> removed.add(n.asText()));
        removed.forEach(row.tags::remove);
        row.tags.putAll(set);
        ObjectNode data = json.createObjectNode();
        data.set("tags", json.valueToTree(row.tags));
        bump(row);
        event(row, "Tagged", actor, null, requestId, data);
        return remember(key, new Answer(row.resource(), false));
    }

    // ---- the platform's feed

    @Override
    public void onFeedEvent(FeedEvent event) {
        if (event == null || event.type() == null) {
            return;
        }
        switch (event.type()) {
            case "job.started" -> jobStarted(event);
            case "job.finished" -> jobFinished(event);
            case "resource.registered" -> registered(event);
            case "resource.retired" -> retired(event);
            default -> { }
        }
    }

    private synchronized void jobStarted(FeedEvent event) {
        String wfrn = event.context() == null ? null : event.context().wfrn();
        Row row = wfrn == null ? null : rows.get(wfrn);
        if (row == null || !row.live()) {
            return;
        }
        String jobId = text(event.data(), "jobId") != null ? text(event.data(), "jobId") : event.aggregateId();
        row.lastJobId = jobId;
        ObjectNode data = json.createObjectNode();
        data.put("workflow", text(event.data(), "workflow"));
        bump(row);
        event(row, "ProvisioningStarted", PLATFORM, jobId, event.context().consoleRequestId(), data);
    }

    private synchronized void jobFinished(FeedEvent event) {
        String wfrn = event.context() == null ? null : event.context().wfrn();
        Row row = wfrn == null ? null : rows.get(wfrn);
        if (row == null || !row.live()) {
            return;
        }
        String jobId = text(event.data(), "jobId") != null ? text(event.data(), "jobId") : event.aggregateId();
        String status = text(event.data(), "status");
        String requestId = event.context().consoleRequestId();
        if ("succeeded".equals(status)) {
            switch (row.state) {
                case StateResource.PENDING -> {
                    row.state = StateResource.ACTIVE;
                    bump(row);
                    event(row, "ProvisioningSucceeded", PLATFORM, jobId, requestId, json.createObjectNode());
                }
                case StateResource.UPDATING -> {
                    if (row.changes != null) {
                        if (row.attributes == null) {
                            row.attributes = json.createObjectNode();
                        }
                        row.attributes.setAll(row.changes);
                    }
                    row.changes = null;
                    row.state = StateResource.ACTIVE;
                    bump(row);
                    event(row, "ProvisioningSucceeded", PLATFORM, jobId, requestId, json.createObjectNode());
                }
                case StateResource.DELETING -> end(row, jobId, requestId);
                default -> { }
            }
            return;
        }
        if ("failed".equals(status) || "cancelled".equals(status) || "rolled-back".equals(status)) {
            ObjectNode error = json.createObjectNode();
            error.put("jobId", jobId);
            error.put("status", status);
            for (String field : new String[] {"errorClass", "failedStep", "message"}) {
                if (text(event.data(), field) != null) {
                    error.put(field, text(event.data(), field));
                }
            }
            row.lastError = error;
            row.changes = null;
            row.state = StateResource.PENDING.equals(row.state) ? StateResource.FAILED : StateResource.ACTIVE;
            bump(row);
            event(row, "ProvisioningFailed", PLATFORM, jobId, requestId, error.deepCopy());
        }
    }

    private void registered(FeedEvent event) {
        JsonNode data = event.data();
        String wfrn = text(data, "wfrn") != null ? text(data, "wfrn") : event.context() == null ? null : event.context().wfrn();
        String registryId = text(data, "id");
        if (wfrn == null || registryId == null) {
            return;
        }
        String accountId = text(data, "accountId") != null ? text(data, "accountId") : event.context() == null ? null : event.context().accountId();
        String region = text(data, "region") != null ? text(data, "region") : event.context() == null ? null : event.context().region();
        String jobId = text(data, "jobId");
        String requestId = event.context() == null ? null : event.context().consoleRequestId();
        clients.internal().flatMap(p -> p.getResource(registryId))
                .subscribe(record -> apply(wfrn, accountId, region, record, jobId, requestId),
                        error -> log.warn("registry record {} of {} is unavailable: {}", registryId, wfrn, error.getMessage()));
    }

    private synchronized void apply(String wfrn, String accountId, String region, Resource record, String jobId, String requestId) {
        Row row = rows.get(wfrn);
        ObjectNode attributes = record.attributes() == null ? json.createObjectNode() : json.valueToTree(record.attributes());
        Instant now = clock.instant();
        if (row == null) {
            if (accountId == null || "retired".equalsIgnoreCase(record.status())) {
                return;
            }
            row = new Row();
            row.wfrn = wfrn;
            row.accountId = accountId;
            row.region = region == null ? "global" : region;
            row.type = record.type();
            row.service = servicesByType.getOrDefault(record.type(), serviceOfWfrn(wfrn));
            row.name = record.name();
            row.state = StateResource.ACTIVE;
            row.version = 0;
            row.registryId = record.id();
            row.providerId = record.providerId();
            row.attributes = attributes;
            row.observed = attributes.deepCopy();
            row.observedAt = now;
            row.observedStatus = "found";
            row.source = "backfill";
            row.createdAt = record.createdAt() == null ? now : record.createdAt();
            row.updatedAt = now;
            rows.put(wfrn, row);
            bump(row);
            ObjectNode data = json.createObjectNode();
            data.put("registryId", record.id());
            data.put("source", "backfill");
            event(row, "Imported", PLATFORM, jobId, requestId, data);
            return;
        }
        if (!row.live()) {
            return;
        }
        row.registryId = record.id();
        row.providerId = record.providerId();
        if (row.name == null) {
            row.name = record.name();
        }
        row.attributes = attributes;
        row.observed = attributes.deepCopy();
        row.observedAt = now;
        row.observedStatus = "found";
        row.lastJobId = jobId != null ? jobId : row.lastJobId;
        ObjectNode data = json.createObjectNode();
        data.put("registryId", record.id());
        if (StateResource.PENDING.equals(row.state) || StateResource.UPDATING.equals(row.state)) {
            row.changes = null;
            row.state = StateResource.ACTIVE;
            bump(row);
            event(row, "ProvisioningSucceeded", PLATFORM, jobId, requestId, data);
        } else {
            bump(row);
            event(row, "Observed", PLATFORM, jobId, requestId, data);
        }
    }

    private synchronized void retired(FeedEvent event) {
        JsonNode data = event.data();
        String wfrn = text(data, "wfrn") != null ? text(data, "wfrn") : event.context() == null ? null : event.context().wfrn();
        Row row = wfrn == null ? null : rows.get(wfrn);
        if (row == null || !row.live()) {
            return;
        }
        end(row, text(data, "jobId"), event.context() == null ? null : event.context().consoleRequestId());
    }

    private void end(Row row, String jobId, String requestId) {
        Instant now = clock.instant();
        row.state = StateResource.DELETED;
        row.deletedAt = now;
        row.tombstoneUntil = now.plus(TOMBSTONE);
        row.changes = null;
        row.lastJobId = jobId != null ? jobId : row.lastJobId;
        bump(row);
        ObjectNode data = json.createObjectNode();
        data.put("tombstoneUntil", row.tombstoneUntil.toString());
        event(row, "Deleted", PLATFORM, jobId, requestId, data);
    }

    // ---- pieces

    private Row live(String accountId, String wfrn) {
        Row row = rows.get(wfrn);
        if (row == null || (accountId != null && !row.accountId.equals(accountId))) {
            throw NotFoundException.of("resource", wfrn);
        }
        if (!row.live()) {
            throw new ConflictException("the resource is deleted", Map.of("state", row.state));
        }
        return row;
    }

    private static void expectVersion(Row row, JsonNode body) {
        if (body.hasNonNull("expectedVersion") && body.get("expectedVersion").asLong() != row.version) {
            throw new ConflictException("the resource is at version " + row.version + ", not " + body.get("expectedVersion").asLong(),
                    Map.of("version", row.version));
        }
    }

    private void bump(Row row) {
        row.version++;
        row.updatedAt = clock.instant();
    }

    private void event(Row row, String type, String actor, String jobId, String requestId, ObjectNode extra) {
        ObjectNode data = extra == null ? json.createObjectNode() : extra;
        data.put("version", row.version);
        data.put("state", row.state);
        data.put("resourceType", row.type);
        if (jobId != null) {
            data.put("jobId", jobId);
        }
        EventEnvelope envelope = EventEnvelope.of("evt_" + eventIds.incrementAndGet(), row.type + ":" + row.wfrn + ":" + row.version, type,
                clock.instant(), "resource", row.wfrn, actor, data)
                .withContext(new EventEnvelope.Context(row.accountId, row.region, row.wfrn, requestId), null);
        row.log.add(envelope);
        ConsoleEvents.toLive(envelope).ifPresent(mapped -> hub.publish(mapped.accountId(), mapped.event()));
    }

    private Answer replay(String key) {
        return key == null || key.isBlank() ? null : replays.get(key) == null ? null : new Answer(replays.get(key).resource(), true);
    }

    private Answer remember(String key, Answer answer) {
        if (key != null && !key.isBlank()) {
            replays.put(key, answer);
        }
        return answer;
    }

    private String freshName(String service, String region, String accountId, String type, String name) {
        String base = name == null ? type : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = type;
        }
        for (int i = 0; i < 100; i++) {
            StringBuilder suffix = new StringBuilder();
            for (int j = 0; j < 6; j++) {
                suffix.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String id = (base + "-" + suffix).length() > 128 ? base.substring(0, 120) + "-" + suffix : base + "-" + suffix;
            String wfrn = "wfrn:wf:" + service + ":" + region + ":" + accountId + ":" + type + "/" + id;
            if (!rows.containsKey(wfrn)) {
                return wfrn;
            }
        }
        throw new IllegalStateException("could not mint a fresh name for " + type);
    }

    /** The service of a type: its resource type's owner on the platform ({@code team-compute} is {@code compute}). */
    private Mono<String> serviceOf(String type) {
        String known = servicesByType.get(type);
        if (known != null) {
            return Mono.just(known);
        }
        return clients.internal().flatMap(p -> p.listResourceTypes(null)).map(c -> c.body() == null ? List.<ResourceType>of() : c.body())
                .flatMap(types -> {
                    for (ResourceType rt : types) {
                        servicesByType.put(rt.name(), Descriptors.serviceOf(rt));
                    }
                    String service = servicesByType.get(type);
                    return service == null
                            ? Mono.error(new InvalidInputException("unknown type", List.of(Problem.error("unknown", "/type", "no resource type " + type))))
                            : Mono.just(service);
                });
    }

    private static String serviceOfWfrn(String wfrn) {
        String[] parts = wfrn.split(":", 6);
        return parts.length > 2 ? parts[2] : "unknown";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** How many resources the stand-in holds, for the demo's diagnostics. */
    public synchronized int size() {
        return rows.size();
    }
}
