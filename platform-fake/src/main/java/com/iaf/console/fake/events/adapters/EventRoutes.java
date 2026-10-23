package com.iaf.console.fake.events.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.common.Sse;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.registry.application.Registry;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.fake.runs.application.Jobs;
import com.iaf.console.fake.runs.domain.Job;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;

import reactor.core.publisher.Mono;

/** The event feed routes (P-01): a page, a stream resumable by sequence, and the snapshot for rebuilds. */
public final class EventRoutes {

    public static final int MAX_LIMIT = 1000;
    public static final int DEFAULT_LIMIT = 100;
    public static final int DEFAULT_SNAPSHOT_LIMIT = 500;

    private final EventFeed events;
    private final Registry registry;
    private final Jobs jobs;

    public EventRoutes(EventFeed events, Registry registry, Jobs jobs) {
        this.events = events;
        this.registry = registry;
        this.jobs = jobs;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(GET("/api/v1/events"), this::list)
                .andRoute(GET("/api/v1/events/stream"), this::stream)
                .andRoute(GET("/api/v1/events/snapshot"), this::snapshot);
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.EVENTS_READ);
        long after = Http.longQuery(request, "after").orElse(0L);
        int limit = Http.limit(request, MAX_LIMIT).orElse(DEFAULT_LIMIT);
        List<EventEnvelope> items = events.list(after, limit, types(request), caller);
        ObjectNode out = Json.object();
        var itemsNode = out.putArray("items");
        for (EventEnvelope event : items) {
            itemsNode.add(Json.mapper().valueToTree(event));
        }
        out.put("next", items.isEmpty() ? Math.max(after, 0) : items.get(items.size() - 1).seq());
        return Http.ok(out);
    }

    private Mono<ServerResponse> stream(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.EVENTS_READ);
        return Sse.envelopes(events.stream(Http.resumeFrom(request, events.lastSeq()), types(request), caller));
    }

    private Mono<ServerResponse> snapshot(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.EVENTS_READ);
        String kind = Http.query(request, "kind").orElseThrow(() -> ApiException.badRequest("kind is required: resources or jobs"));
        int limit = Http.limit(request, MAX_LIMIT).orElse(DEFAULT_SNAPSHOT_LIMIT);
        long updatedAfter = Http.longQuery(request, "updatedAfter").orElse(-1L);
        Optional<String> cursor = Http.query(request, "cursor");
        long highWater = events.lastSeq();
        long afterNs = updatedAfter;
        String afterId = null;
        if (cursor.isPresent()) {
            Cursor decoded = CursorPaging.decode(cursor.get());
            afterNs = decoded.sortLong();
            int split = decoded.id().indexOf(':');
            if (split < 1) {
                throw ApiException.badRequest("the cursor is not one this service issued");
            }
            highWater = Long.parseLong(decoded.id().substring(0, split));
            afterId = decoded.id().substring(split + 1);
        }
        List<ObjectNode> documents = new ArrayList<>();
        String nextCursor = null;
        switch (kind) {
            case "resources" -> {
                List<ResourceRecord> all = registry.changedAfter(afterNs - 1, caller);
                for (ResourceRecord record : all) {
                    if (skip(record.updatedNs(), record.id(), afterNs, afterId, cursor.isPresent())) {
                        continue;
                    }
                    if (documents.size() == limit) {
                        ResourceRecord last = all.get(all.indexOf(record) - 1);
                        nextCursor = CursorPaging.encode(last.updatedNs(), highWater + ":" + last.id());
                        break;
                    }
                    documents.add(record.document());
                }
            }
            case "jobs" -> {
                List<Job> all = jobs.changedAfter(afterNs - 1, caller);
                for (Job job : all) {
                    if (skip(job.updatedNs(), job.id(), afterNs, afterId, cursor.isPresent())) {
                        continue;
                    }
                    if (documents.size() == limit) {
                        Job last = all.get(all.indexOf(job) - 1);
                        nextCursor = CursorPaging.encode(last.updatedNs(), highWater + ":" + last.id());
                        break;
                    }
                    documents.add(job.summary());
                }
            }
            default -> throw ApiException.badRequest("kind must be resources or jobs");
        }
        ObjectNode out = Json.object();
        var items = out.putArray("items");
        documents.forEach(items::add);
        if (nextCursor != null) {
            out.put("next", nextCursor);
        }
        out.put("highWaterSeq", highWater);
        return Http.ok(out);
    }

    /** Whether a record is at or before the cursor's position in (updatedNs, id) order. */
    private static boolean skip(long updatedNs, String id, long afterNs, String afterId, boolean fromCursor) {
        if (!fromCursor) {
            return updatedNs <= afterNs;
        }
        return updatedNs < afterNs || (updatedNs == afterNs && id.compareTo(afterId) <= 0);
    }

    private static List<String> types(ServerRequest request) {
        List<String> out = new ArrayList<>();
        Http.query(request, "types").ifPresent(value -> {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        });
        return out;
    }
}
