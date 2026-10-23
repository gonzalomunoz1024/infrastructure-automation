package com.iaf.console.fake.registry.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.Expressions;
import com.iaf.console.fake.content.domain.ProviderDef;
import com.iaf.console.fake.content.domain.RegionDef;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.providers.domain.ProviderFailure;
import com.iaf.console.fake.providers.domain.RecordedWorld;
import com.iaf.console.fake.quotas.application.Quotas;
import com.iaf.console.fake.registry.domain.ResourceRecord;

/**
 * The observe reads of P-09: one resource through its type's {@code observe} action, a page of a
 * type through its {@code list} or {@code discover} action, and the metrics action, every answer
 * mapped through {@code observe.map} and masked for sensitive fields, never the raw response.
 */
public final class Observer {

    /** The most items one observe-list or discover page holds. */
    public static final int MAX_PAGE = 500;
    public static final int DEFAULT_PAGE = 100;
    public static final String MASK = "***";

    private final FakeClock clock;
    private final ContentStore content;
    private final Registry registry;
    private final RecordedWorld world;
    private final Quotas quotas;

    public Observer(FakeClock clock, ContentStore content, Registry registry, RecordedWorld world, Quotas quotas) {
        this.clock = clock;
        this.content = content;
        this.registry = registry;
        this.world = world;
        this.quotas = quotas;
    }

    /** Observes one resource: {@code found}, {@code not_found} or {@code error}, with the mapped fields. */
    public ObjectNode observe(ResourceRecord record, Caller caller) {
        ResourceTypeDef type = content.type(record.type()).orElseThrow(() -> ApiException.notFound("resource type", record.type()));
        if (!type.hasObserve()) {
            throw new ApiException(404, "observe.notSupported");
        }
        JsonNode observe = type.observe();
        String providerName = observe.path("provider").asText();
        ProviderDef provider = content.provider(providerName).orElseThrow(() -> new ApiException(404, "observe.notSupported"));
        String action = observe.path("action").asText();
        if (!"read".equals(provider.effect(action))) {
            throw ApiException.forbidden("observe.notRead");
        }
        RegionDef region = regionOf(record.region());
        String environment = record.environment() != null ? record.environment() : region.scope(null).path("environment").asText();
        quotas.chargeObserve(providerName, region.name(), caller.accountId());
        Expressions.MapScope scope = new Expressions.MapScope()
                .put("resource", record.document())
                .put("region", region.scope(environment));
        JsonNode input = Expressions.render(observe.path("input"), scope);
        Instant at = clock.now();
        ObjectNode out = Json.object();
        try {
            JsonNode response = world.call(provider, action, input, null);
            out.put("status", "found");
            out.set("observed", map(type, observe.path("map"), response, scope));
        } catch (ProviderFailure e) {
            if (e.notFound()) {
                out.put("status", "not_found");
            } else {
                out.put("status", "error");
                ObjectNode error = out.putObject("error");
                error.put("class", e.errorClass());
                error.put("message", e.getMessage());
                error.put("retryable", e.retryable());
            }
        }
        out.put("provider", providerName);
        out.put("action", action);
        out.put("providerVersion", provider.version());
        out.put("region", region.name());
        out.put("environment", environment);
        out.put("at", at.toString());
        out.put("durationMs", Math.max(0, clock.now().toEpochMilli() - at.toEpochMilli()));
        out.put("id", record.id());
        if (record.wfrn() != null) {
            out.put("wfrn", record.wfrn());
        }
        return out;
    }

    /** Up to 100 resources by id or wfrn, one result per entry in order; an unknown one is {@code not_found}. */
    public ObjectNode observeMany(JsonNode body, Caller caller) {
        List<String> ids = new ArrayList<>();
        for (JsonNode id : body.path("ids")) {
            ids.add(id.asText());
        }
        List<String> wfrns = new ArrayList<>();
        for (JsonNode wfrn : body.path("wfrns")) {
            wfrns.add(wfrn.asText());
        }
        if (ids.size() + wfrns.size() > 100) {
            throw ApiException.badRequest("at most 100 resources per call");
        }
        ObjectNode out = Json.object();
        ArrayNode results = out.putArray("results");
        for (String id : ids) {
            results.add(registry.visible(id, caller).map(r -> observe(r, caller)).orElseGet(() -> unknown(id, null)));
        }
        for (String wfrn : wfrns) {
            results.add(registry.byWfrn(wfrn).filter(r -> Registry.sees(caller, r)).map(r -> observe(r, caller))
                    .orElseGet(() -> unknown(null, wfrn)));
        }
        return out;
    }

    private ObjectNode unknown(String id, String wfrn) {
        ObjectNode out = Json.object();
        out.put("status", "not_found");
        out.put("provider", "registry");
        out.put("action", "lookup");
        out.put("region", content.defaultRegion().name());
        out.put("at", clock.now().toString());
        out.put("durationMs", 0);
        if (id != null) {
            out.put("id", id);
        }
        if (wfrn != null) {
            out.put("wfrn", wfrn);
        }
        return out;
    }

    /**
     * One page of a type's provider objects through its list action (discover's when it has no
     * list): every item mapped, with {@code managed} and the wfrn from the registry; managed items
     * only when {@code managedOnly}, and one account's when {@code account} is set.
     */
    public ObjectNode page(ResourceTypeDef type, JsonNode body, Caller caller, boolean managedOnly) {
        if (!type.hasObserve()) {
            throw new ApiException(404, "observe.notSupported");
        }
        JsonNode observe = type.observe();
        JsonNode paged = observe.path("list").isObject() && !managedOnly ? observe.path("list")
                : observe.path("list").isObject() ? observe.path("list") : observe.path("discover");
        if (!paged.isObject()) {
            throw new ApiException(404, "observe.listNotSupported");
        }
        String regionName = body.path("region").asText(null);
        String environment = body.path("environment").asText(null);
        if (regionName == null || environment == null) {
            throw ApiException.badRequest("region and environment are required");
        }
        RegionDef region = content.region(regionName).orElseThrow(() -> ApiException.notFound("region", regionName));
        if (!region.hasEnvironment(environment)) {
            throw ApiException.badRequest("region " + regionName + " has no environment " + environment);
        }
        String account = body.path("account").asText(null);
        if (caller.isConsoleAccount()) {
            account = caller.consoleAccount();
        }
        int pageSize = body.path("pageSize").isIntegralNumber() ? body.path("pageSize").asInt() : DEFAULT_PAGE;
        if (pageSize < 1 || pageSize > MAX_PAGE) {
            throw ApiException.badRequest("pageSize must be between 1 and " + MAX_PAGE);
        }
        int offset = offsetOf(body.path("cursor").asText(null));
        String providerName = observe.path("provider").asText();
        ProviderDef provider = content.provider(providerName).orElseThrow(() -> new ApiException(404, "observe.notSupported"));
        String action = paged.path("action").asText(observe.path("action").asText());
        if (!"read".equals(provider.effect(action))) {
            throw ApiException.forbidden("observe.notRead");
        }
        quotas.chargeObserve(providerName, region.name(), account);
        Expressions.MapScope scope = new Expressions.MapScope().put("region", region.scope(environment));
        JsonNode input = Expressions.render(paged.path("input"), scope);
        Instant at = clock.now();
        JsonNode response;
        try {
            response = world.call(provider, action, input, null);
        } catch (ProviderFailure e) {
            throw new ApiException(e.notFound() ? 404 : 502, "observe." + e.errorClass());
        }
        Expressions.MapScope responseScope = scope.copy().put("json", response);
        JsonNode items = paged.path("items").isTextual() ? Expressions.render(paged.path("items"), responseScope) : response.path("items");
        List<ObjectNode> all = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        if (items.isArray()) {
            for (JsonNode raw : items) {
                JsonNode item = raw.isObject() ? world.withChanges(providerName, (ObjectNode) raw) : raw;
                String providerId = paged.path("id").isTextual()
                        ? Expressions.asText(Expressions.render(paged.path("id"), scope.copy().put("item", item)))
                        : item.path("id").asText(null);
                if (providerId == null || providerId.isBlank() || world.isDeleted(providerName, providerId)) {
                    continue;
                }
                seen.add(providerId);
                all.add(item(type, observe, item, providerId, providerName, region.name(), scope));
            }
        }
        for (ResourceRecord record : registry.list(new Registry.Filter(type.name(), null, null, null, null, null), caller)) {
            if (record.retired() || record.providerId() == null || seen.contains(record.providerId()) || seen.contains(record.id())) {
                continue;
            }
            if (record.region() != null && !record.region().equals(region.name())) {
                continue;
            }
            JsonNode item = world.withChanges(providerName, observedOf(record));
            seen.add(record.providerId());
            all.add(item(type, observe, item, record.providerId(), providerName, region.name(), scope));
        }
        List<ObjectNode> selected = new ArrayList<>();
        for (ObjectNode item : all) {
            boolean managed = item.path("managed").asBoolean(false);
            if (managedOnly && !managed) {
                continue;
            }
            if (account != null && !account.equals(item.path("accountId").asText(null))) {
                continue;
            }
            item.remove("accountId");
            selected.add(item);
        }
        ObjectNode out = Json.object();
        ArrayNode page = out.putArray("items");
        int end = Math.min(selected.size(), offset + pageSize);
        for (int i = offset; i < end; i++) {
            page.add(selected.get(i));
        }
        if (end < selected.size()) {
            out.put("next", Integer.toString(end));
        }
        out.put("provider", providerName);
        out.put("action", action);
        out.put("at", at.toString());
        return out;
    }

    /** The provider's view of a registered resource when the recorded list does not have it: its id and attributes. */
    private static ObjectNode observedOf(ResourceRecord record) {
        ObjectNode item = record.attributes();
        item.put("id", record.providerId());
        return item;
    }

    private ObjectNode item(ResourceTypeDef type, JsonNode observe, JsonNode raw, String providerId, String providerName, String region,
            Expressions.MapScope scope) {
        ObjectNode out = Json.object();
        out.put("providerId", providerId);
        ResourceRecord record = registry.byProviderId(providerName, region, providerId).orElse(null);
        if (record != null && record.wfrn() != null) {
            out.put("wfrn", record.wfrn());
        }
        if (record != null) {
            out.put("id", record.id());
            if (record.accountId() != null) {
                out.put("accountId", record.accountId());
            }
        }
        out.set("observed", map(type, observe.path("map"), raw, scope));
        out.put("managed", record != null && !record.retired());
        return out;
    }

    /** Metrics through the type's metrics action; 404 when the type has none. */
    public ObjectNode metrics(ResourceRecord record, JsonNode body, Caller caller) {
        ResourceTypeDef type = content.type(record.type()).orElseThrow(() -> ApiException.notFound("resource type", record.type()));
        if (!type.hasMetrics()) {
            throw new ApiException(404, "observe.notSupported");
        }
        JsonNode metrics = type.observe().path("metrics");
        String providerName = type.observe().path("provider").asText();
        ProviderDef provider = content.provider(providerName).orElseThrow(() -> new ApiException(404, "observe.notSupported"));
        String action = metrics.path("action").asText();
        if (!"read".equals(provider.effect(action))) {
            throw ApiException.forbidden("observe.notRead");
        }
        if (!body.hasNonNull("from") || !body.hasNonNull("to")) {
            throw ApiException.badRequest("from and to are required");
        }
        RegionDef region = regionOf(record.region());
        quotas.chargeObserve(providerName, region.name(), caller.accountId());
        Expressions.MapScope scope = new Expressions.MapScope().put("resource", record.document()).put("region", region.scope(record.environment()))
                .put("range", body);
        JsonNode input = Expressions.render(metrics.path("input"), scope);
        JsonNode response = world.call(provider, action, input, null);
        Expressions.MapScope responseScope = scope.copy().put("json", response);
        ObjectNode out = Json.object();
        ArrayNode series = out.putArray("series");
        for (Map.Entry<String, JsonNode> entry : metrics.path("map").properties()) {
            ObjectNode one = series.addObject();
            one.put("name", entry.getKey());
            JsonNode value = Expressions.render(entry.getValue(), responseScope);
            if (value.isObject()) {
                one.setAll((ObjectNode) value);
            } else {
                one.set("points", value.isArray() ? value : Json.array());
            }
        }
        out.put("provider", providerName);
        out.put("action", action);
        out.put("at", clock.now().toString());
        return out;
    }

    private RegionDef regionOf(String name) {
        if (name == null) {
            return content.defaultRegion();
        }
        return content.region(name).orElseGet(content::defaultRegion);
    }

    private static int offsetOf(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.trim());
            if (offset < 0) {
                throw ApiException.badRequest("the cursor is not one this service issued");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("the cursor is not one this service issued");
        }
    }

    /** The mapped fields of a response, sensitive ones masked. */
    static ObjectNode map(ResourceTypeDef type, JsonNode mapping, JsonNode response, Expressions.MapScope scope) {
        Expressions.MapScope responseScope = scope.copy().put("json", response);
        ObjectNode out = Json.object();
        Set<String> sensitive = type.sensitiveFields();
        if (mapping.isObject()) {
            for (Map.Entry<String, JsonNode> field : mapping.properties()) {
                JsonNode value = Expressions.render(field.getValue(), responseScope);
                out.set(field.getKey(), sensitive.contains(field.getKey()) ? Json.mapper().getNodeFactory().textNode(MASK) : value);
            }
        }
        return out;
    }
}
