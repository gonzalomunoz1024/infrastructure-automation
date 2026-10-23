package com.iaf.console.search.index.adapters.opensearch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.Json;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.SearchHit;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The index in OpenSearch, over its REST API. Everything goes through an alias ({@code console-resources}
 * by default) whose write index is {@code <alias>-v<mapping version>}, so a new mapping is a reindex
 * and an alias swap, and a write never creates an index by accident ({@code require_alias}).
 * Documents are routed by account and written with the resource's version as an external version
 * (greater or equal: a stale write is a no-op, a rewrite at the same version goes through).
 * Searches filter on keyword fields, score a text query over the name (exact, prefix, words), the
 * id, the tags and the searchable fields, page with {@code search_after} on {@code (sort value, wfrn)},
 * and highlight. The mapping is {@code opensearch/console-resources.json}.
 */
public final class OpenSearchIndex implements SearchIndexPort {

    public static final String MAPPING = "opensearch/console-resources.json";
    /** The version of the mapping; the concrete index is the alias with {@code -v} and this number. */
    public static final int MAPPING_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndex.class);
    private static final int MAX_TOTAL = 10_000;
    /** Where a document without a name sorts by name: after every name. */
    static final String NO_NAME = "￿";

    private final WebClient web;
    private final String alias;
    private final String refresh;
    private final ObjectMapper json;
    private final Clock clock;

    public OpenSearchIndex(WebClient web, String alias, String refresh, Clock clock) {
        this.web = web;
        this.alias = alias;
        this.refresh = refresh == null || refresh.isBlank() ? "false" : refresh;
        this.json = Json.MAPPER;
        this.clock = clock;
    }

    /** The concrete index the alias writes to. */
    public String concreteIndex() {
        return alias + "-v" + MAPPING_VERSION;
    }

    public String alias() {
        return alias;
    }

    // readiness

    @Override
    public Mono<Void> ensureReady() {
        return web.head().uri("/_alias/{alias}", alias).exchangeToMono(r -> {
            if (r.statusCode().is2xxSuccessful()) {
                return r.releaseBody();
            }
            if (r.statusCode().value() == 404) {
                return r.releaseBody().then(create());
            }
            return failure(r);
        });
    }

    private Mono<Void> create() {
        ObjectNode body;
        try (InputStream in = OpenSearchIndex.class.getClassLoader().getResourceAsStream(MAPPING)) {
            if (in == null) {
                return Mono.error(new IllegalStateException("no " + MAPPING + " on the classpath"));
            }
            body = (ObjectNode) json.readTree(in);
        } catch (IOException e) {
            return Mono.error(e);
        }
        body.putObject("aliases").putObject(alias).put("is_write_index", true);
        return web.put().uri("/{index}", concreteIndex()).contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
                .exchangeToMono(r -> {
                    if (r.statusCode().is2xxSuccessful()) {
                        log.info("index {} created behind the alias {}", concreteIndex(), alias);
                        return r.releaseBody();
                    }
                    return r.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> {
                        if (text.contains("resource_already_exists_exception")) {
                            // another replica created it first, or the alias was removed by hand: (re)attach it
                            return attachAlias();
                        }
                        return Mono.error(new OpenSearchException(r.statusCode().value(), text));
                    });
                });
    }

    private Mono<Void> attachAlias() {
        ObjectNode body = json.createObjectNode();
        body.putArray("actions").addObject().putObject("add").put("index", concreteIndex()).put("alias", alias).put("is_write_index", true);
        return web.post().uri("/_aliases").contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.releaseBody() : failure(r));
    }

    /** Whether the cluster answers for the alias, for the health check. */
    public Mono<Boolean> ping() {
        return web.head().uri("/_alias/{alias}", alias).exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().is2xxSuccessful()))
                .onErrorReturn(false);
    }

    // writes

    @Override
    public Mono<Void> index(IndexedResource document) {
        return web.put()
                .uri(b -> b.path("/{alias}/_doc/{id}").queryParam("routing", document.accountId()).queryParam("version", document.version())
                        .queryParam("version_type", "external_gte").queryParam("require_alias", true).queryParam("refresh", refresh)
                        .build(alias, document.wfrn()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(toDocument(document))
                .exchangeToMono(r -> {
                    if (r.statusCode().is2xxSuccessful() || r.statusCode().value() == 409) {
                        // 409: the index holds a newer version, which wins
                        return r.releaseBody();
                    }
                    return failure(r);
                });
    }

    @Override
    public Mono<Integer> indexAll(List<IndexedResource> documents) {
        if (documents.isEmpty()) {
            return Mono.just(0);
        }
        StringBuilder body = new StringBuilder();
        for (IndexedResource d : documents) {
            ObjectNode action = json.createObjectNode();
            action.putObject("index").put("_index", alias).put("_id", d.wfrn()).put("routing", d.accountId()).put("version", d.version())
                    .put("version_type", "external_gte").put("require_alias", true);
            body.append(action).append('\n').append(toDocument(d)).append('\n');
        }
        return web.post().uri(b -> b.path("/_bulk").queryParam("refresh", refresh).build())
                .contentType(MediaType.parseMediaType("application/x-ndjson")).bodyValue(body.toString())
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class) : OpenSearchIndex.<JsonNode>failure(r))
                .map(response -> {
                    int written = 0;
                    for (JsonNode item : response.path("items")) {
                        JsonNode op = item.path("index");
                        int status = op.path("status").asInt();
                        if (status >= 200 && status < 300) {
                            written++;
                        } else if (status != 409) {
                            throw new OpenSearchException(status, op.path("error").toString());
                        }
                    }
                    return written;
                });
    }

    @Override
    public Mono<Void> remove(String accountId, String wfrn) {
        return web.delete()
                .uri(b -> b.path("/{alias}/_doc/{id}").queryParam("routing", accountId).queryParam("refresh", refresh).build(alias, wfrn))
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() || r.statusCode().value() == 404 ? r.releaseBody() : failure(r));
    }

    // reads

    @Override
    public Mono<IndexedResource> find(AccountScope scope, String wfrn) {
        return web.get().uri(b -> b.path("/{alias}/_doc/{id}").queryParam("routing", scope.accountId()).build(alias, wfrn))
                .exchangeToMono(r -> {
                    if (r.statusCode().value() == 404) {
                        return r.releaseBody().then(Mono.<JsonNode>empty());
                    }
                    if (!r.statusCode().is2xxSuccessful()) {
                        return OpenSearchIndex.<JsonNode>failure(r);
                    }
                    return r.bodyToMono(JsonNode.class);
                })
                .flatMap(node -> Mono.justOrEmpty(fromDocument(node.path("_source"))))
                .filter(d -> d.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<SearchPage> search(AccountScope scope, SearchQuery query) {
        ObjectNode body = json.createObjectNode();
        body.put("size", query.limit() + 1);
        body.put("track_total_hits", MAX_TOTAL);
        body.set("query", queryOf(scope, query));
        ArrayNode sort = body.putArray("sort");
        String order = query.sort().descending() ? "desc" : "asc";
        switch (query.sort().key()) {
            case SCORE -> sort.addObject().put("_score", "desc");
            case NAME -> sort.addObject().putObject("sortName").put("order", order);
            case CREATED -> sort.addObject().putObject("createdAt").put("order", order).put("missing", "_last");
            case UPDATED -> sort.addObject().putObject("updatedAt").put("order", order).put("missing", "_last");
        }
        sort.addObject().put("wfrn", "asc");
        if (query.after() != null) {
            ArrayNode after = body.putArray("search_after");
            after.addPOJO(query.after().sortValue());
            after.add(query.after().id());
        }
        if (query.hasTerms()) {
            ObjectNode highlight = body.putObject("highlight");
            highlight.putArray("pre_tags").add("<em>");
            highlight.putArray("post_tags").add("</em>");
            highlight.put("require_field_match", false);
            // the text around the tags is HTML-escaped, so a page may render a highlight as markup
            highlight.put("encoder", "html");
            ObjectNode fields = highlight.putObject("fields");
            fields.putObject("name").put("number_of_fragments", 0);
            fields.putObject("text").put("number_of_fragments", 1).put("fragment_size", 120);
        }
        return post("/{alias}/_search", scope.accountId(), body).map(response -> {
            List<JsonNode> hits = new ArrayList<>();
            response.path("hits").path("hits").forEach(hits::add);
            boolean more = hits.size() > query.limit();
            List<JsonNode> kept = more ? hits.subList(0, query.limit()) : hits;
            List<SearchHit> items = new ArrayList<>(kept.size());
            for (JsonNode h : kept) {
                IndexedResource d = fromDocument(h.path("_source"));
                if (d == null) {
                    continue;
                }
                double score = h.path("_score").isNumber() ? h.path("_score").asDouble() : 1.0;
                items.add(SearchHit.of(d, Math.round(score * 1000.0) / 1000.0, highlightsOf(h.path("highlight"))));
            }
            String next = null;
            if (more && !kept.isEmpty()) {
                JsonNode last = kept.get(kept.size() - 1);
                next = CursorPaging.encode(new Cursor(sortValueOf(last.path("sort").get(0)), last.path("_source").path("wfrn").asText()));
            }
            JsonNode total = response.path("hits").path("total");
            Long count = "eq".equals(total.path("relation").asText()) ? total.path("value").asLong() : null;
            return new SearchPage(items, next, count, response.path("took").asLong());
        });
    }

    private ObjectNode queryOf(AccountScope scope, SearchQuery query) {
        return queryOf(scope, query, true, true);
    }

    /**
     * The query of a search: the filters, the state (or the tombstones left out) when
     * {@code withState}, and every term, which must match somewhere, scored when {@code scoring}.
     */
    private ObjectNode queryOf(AccountScope scope, SearchQuery query, boolean withState, boolean scoring) {
        ObjectNode root = json.createObjectNode();
        ObjectNode bool = root.putObject("bool");
        ArrayNode filter = array(bool, "filter");
        term(filter, "accountId", scope.accountId());
        if (query.region() != null) {
            term(filter, "region", query.region());
        }
        if (query.type() != null) {
            term(filter, "type", query.type());
        }
        if (query.drift() != null) {
            filter.addObject().putObject("term").put("drift", query.drift());
        }
        for (Map.Entry<String, String> tag : query.tags().entrySet()) {
            if (tag.getValue().isEmpty()) {
                filter.addObject().putObject("prefix").put("tagsFlat", tag.getKey() + "=");
            } else {
                term(filter, "tagsFlat", tag.getKey() + "=" + tag.getValue());
            }
        }
        if (!query.wfrnPatterns().isEmpty()) {
            ObjectNode any = filter.addObject().putObject("bool");
            any.put("minimum_should_match", 1);
            ArrayNode should = any.putArray("should");
            for (String pattern : query.wfrnPatterns()) {
                // wfrn.pattern is a wildcard field (n-grams checked against the value), so a pattern costs the same on 10 or 10M names
                should.addObject().putObject("wildcard").putObject("wfrn.pattern").put("value", pattern);
            }
        }
        if (withState) {
            stateClause(bool, query);
        }
        if (!query.hasTerms()) {
            if (scoring) {
                array(bool, "must").addObject().putObject("match_all");
            }
            return root;
        }
        ArrayNode into = scoring ? array(bool, "must") : filter;
        for (String term : query.terms()) {
            String lower = term.toLowerCase(Locale.ROOT);
            ObjectNode one = into.addObject().putObject("bool");
            one.put("minimum_should_match", 1);
            ArrayNode should = one.putArray("should");
            should.addObject().putObject("term").putObject("name.exact").put("value", lower).put("boost", 10);
            should.addObject().putObject("prefix").putObject("name.exact").put("value", lower).put("boost", 6);
            should.addObject().putObject("match").putObject("name.prefix").put("query", lower).put("boost", 4).put("operator", "and");
            should.addObject().putObject("match").putObject("name").put("query", lower).put("boost", 3).put("operator", "and");
            should.addObject().putObject("term").putObject("id").put("value", lower).put("boost", 5);
            should.addObject().putObject("prefix").putObject("id").put("value", lower).put("boost", 5);
            should.addObject().putObject("match").putObject("id.prefix").put("query", lower).put("boost", 2.5).put("operator", "and");
            should.addObject().putObject("term").putObject("wfrn").put("value", term).put("boost", 5);
            should.addObject().putObject("term").putObject("tagsFlat").put("value", term).put("boost", 2);
            should.addObject().putObject("match").putObject("tagsFlat.text").put("query", lower).put("boost", 1.5).put("operator", "and");
            should.addObject().putObject("match").putObject("text").put("query", lower).put("boost", 1).put("operator", "and");
            if (lower.contains(" ")) {
                should.addObject().putObject("match_phrase").putObject("text").put("query", lower).put("boost", 2);
            }
        }
        return root;
    }

    /** The state asked for, else the tombstones left out unless the query asks for them. */
    private static void stateClause(ObjectNode bool, SearchQuery query) {
        if (query.state() != null) {
            term(array(bool, "filter"), "state", query.state().wire());
        } else if (!query.includeDeleted()) {
            term(array(bool, "must_not"), "state", ResourceState.DELETED.wire());
        }
    }

    private static ArrayNode array(ObjectNode node, String field) {
        JsonNode held = node.get(field);
        return held instanceof ArrayNode a ? a : node.putArray(field);
    }

    private static void term(ArrayNode into, String field, String value) {
        into.addObject().putObject("term").put(field, value);
    }

    private static Map<String, String> highlightsOf(JsonNode highlight) {
        Map<String, String> out = new LinkedHashMap<>();
        if (highlight == null || !highlight.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : highlight.properties()) {
            if (e.getValue().isArray() && !e.getValue().isEmpty()) {
                out.put(e.getKey(), e.getValue().get(0).asText());
            }
        }
        if (out.containsKey("name")) {
            out.remove("text");
        }
        return out;
    }

    @Override
    public Mono<List<Suggestion>> suggest(AccountScope scope, SuggestQuery query) {
        String prefix = query.prefix();
        ObjectNode body = json.createObjectNode();
        body.put("size", query.limit());
        body.put("track_total_hits", false);
        ObjectNode bool = body.putObject("query").putObject("bool");
        liveScope(bool, scope, query.type());
        bool.put("minimum_should_match", 1);
        ArrayNode should = bool.putArray("should");
        should.addObject().putObject("prefix").putObject("name.exact").put("value", prefix).put("boost", 4);
        should.addObject().putObject("prefix").putObject("id").put("value", prefix).put("boost", 3);
        should.addObject().putObject("prefix").putObject("wfrn").put("value", prefix).put("case_insensitive", true).put("boost", 3);
        ArrayNode sort = body.putArray("sort");
        sort.addObject().put("_score", "desc");
        sort.addObject().putObject("sortName").put("order", "asc");
        sort.addObject().put("wfrn", "asc");
        // types and tags count every live document of the account, not only the resources found
        ObjectNode everything = body.putObject("aggs").putObject("everything");
        everything.putObject("global");
        ObjectNode live = everything.putObject("aggs").putObject("live");
        liveScope(live.putObject("filter").putObject("bool"), scope, query.type());
        ObjectNode counts = live.putObject("aggs");
        counts.putObject("types").putObject("terms").put("field", "type").put("size", query.limit()).put("include", regexEscape(prefix) + ".*");
        counts.putObject("tags").putObject("terms").put("field", "tagsFlat").put("size", query.limit())
                .put("include", regexEscape(prefix) + ".*|[^=]*=" + regexEscape(prefix) + ".*");
        return post("/{alias}/_search", scope.accountId(), body).map(response -> {
            List<Suggestion> out = new ArrayList<>();
            for (JsonNode h : response.path("hits").path("hits")) {
                IndexedResource d = fromDocument(h.path("_source"));
                if (d == null) {
                    continue;
                }
                boolean byName = d.name() != null && d.name().toLowerCase(Locale.ROOT).startsWith(prefix);
                out.add(byName ? Suggestion.resource(d) : Suggestion.wfrn(d));
            }
            JsonNode aggregations = response.path("aggregations").path("everything").path("live");
            for (JsonNode b : aggregations.path("types").path("buckets")) {
                out.add(Suggestion.type(b.path("key").asText(), b.path("doc_count").asLong()));
            }
            for (JsonNode b : aggregations.path("tags").path("buckets")) {
                out.add(Suggestion.tag(b.path("key").asText(), b.path("doc_count").asLong()));
            }
            return out.size() <= query.limit() ? out : new ArrayList<>(out.subList(0, query.limit()));
        });
    }

    /** The account's live documents (of one type, when given): the filter of every suggestion. */
    private static void liveScope(ObjectNode bool, AccountScope scope, String type) {
        ArrayNode filter = bool.putArray("filter");
        term(filter, "accountId", scope.accountId());
        if (type != null) {
            term(filter, "type", type);
        }
        term(bool.putArray("must_not"), "state", ResourceState.DELETED.wire());
    }

    @Override
    public Mono<ResourceCounts> counts(AccountScope scope, SearchQuery query) {
        ObjectNode body = json.createObjectNode();
        body.put("size", 0);
        body.put("track_total_hits", false);
        // every filter but the state, tombstones included: the chips
        body.set("query", queryOf(scope, query, false, false));
        ObjectNode aggs = body.putObject("aggs");
        aggs.putObject("states").putObject("terms").put("field", "state").put("size", ResourceState.values().length);
        // what the list shows: the state asked for, or the tombstones left out
        ObjectNode listed = aggs.putObject("listed");
        stateClause(listed.putObject("filter").putObject("bool"), query);
        listed.putObject("aggs").putObject("drift").putObject("filter").putObject("term").put("drift", true);
        return post("/{alias}/_search", scope.accountId(), body).map(response -> {
            JsonNode aggregations = response.path("aggregations");
            Map<String, Long> byState = new LinkedHashMap<>();
            for (JsonNode b : aggregations.path("states").path("buckets")) {
                byState.put(b.path("key").asText(), b.path("doc_count").asLong());
            }
            JsonNode shown = aggregations.path("listed");
            return ResourceCounts.exact(shown.path("doc_count").asLong(), byState, shown.path("drift").path("doc_count").asLong());
        });
    }

    /** A literal for a Lucene regular expression: every character but letters and digits escaped. */
    static String regexEscape(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() * 2);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')) {
                sb.append(c);
            } else {
                sb.append('\\').append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public Mono<Long> count() {
        return web.get().uri("/{alias}/_count", alias)
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class) : OpenSearchIndex.<JsonNode>failure(r))
                .map(n -> n.path("count").asLong());
    }

    @Override
    public Mono<Long> count(AccountScope scope) {
        ObjectNode body = json.createObjectNode();
        term(body.putObject("query").putObject("bool").putArray("filter"), "accountId", scope.accountId());
        return post("/{alias}/_count", scope.accountId(), body).map(n -> n.path("count").asLong());
    }

    @Override
    public Mono<Long> removeStale(AccountScope scope, Instant indexedBefore) {
        ObjectNode body = json.createObjectNode();
        ArrayNode filter = body.putObject("query").putObject("bool").putArray("filter");
        term(filter, "accountId", scope.accountId());
        filter.addObject().putObject("range").putObject("indexedAt").put("lt", indexedBefore.toString());
        return deleteByQuery(scope.accountId(), body);
    }

    @Override
    public Mono<Long> expireTombstones(Instant now) {
        ObjectNode body = json.createObjectNode();
        ArrayNode filter = body.putObject("query").putObject("bool").putArray("filter");
        term(filter, "state", ResourceState.DELETED.wire());
        filter.addObject().putObject("range").putObject("tombstoneUntil").put("lt", now.toString());
        return deleteByQuery(null, body);
    }

    private Mono<Long> deleteByQuery(String routing, ObjectNode body) {
        return web.post()
                .uri(b -> {
                    b.path("/{alias}/_delete_by_query").queryParam("conflicts", "proceed").queryParam("refresh", "true");
                    if (routing != null) {
                        b.queryParam("routing", routing);
                    }
                    return b.build(alias);
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class) : OpenSearchIndex.<JsonNode>failure(r))
                .map(n -> n.path("deleted").asLong());
    }

    @Override
    public Flux<String> listAccountIds() {
        return accountsAfter(null).expand(page -> page.after() == null ? Mono.empty() : accountsAfter(page.after()))
                .concatMapIterable(AccountsPage::ids);
    }

    private record AccountsPage(List<String> ids, JsonNode after) {
    }

    /** One page of the account ids, from a composite aggregation, so any number of accounts is walked. */
    private Mono<AccountsPage> accountsAfter(JsonNode after) {
        ObjectNode body = json.createObjectNode();
        body.put("size", 0);
        ObjectNode composite = body.putObject("aggs").putObject("accounts").putObject("composite");
        composite.put("size", 1000);
        composite.putArray("sources").addObject().putObject("accountId").putObject("terms").put("field", "accountId");
        if (after != null) {
            composite.set("after", after);
        }
        return post("/{alias}/_search", null, body).map(response -> {
            JsonNode agg = response.path("aggregations").path("accounts");
            List<String> ids = new ArrayList<>();
            agg.path("buckets").forEach(b -> ids.add(b.path("key").path("accountId").asText()));
            JsonNode next = agg.get("after_key");
            return new AccountsPage(ids, ids.isEmpty() || next == null || next.isNull() ? null : next);
        });
    }

    // documents

    /** The stored document: the resource plus the derived fields the queries need. */
    ObjectNode toDocument(IndexedResource d) {
        ObjectNode doc = json.valueToTree(d);
        doc.put("id", d.id());
        doc.put("sortName", d.name() == null ? NO_NAME : d.name().toLowerCase(Locale.ROOT));
        ArrayNode flat = doc.putArray("tagsFlat");
        d.tagsFlat().forEach(flat::add);
        doc.put("text", d.text());
        doc.put("indexedAt", clock.instant().toString());
        return doc;
    }

    IndexedResource fromDocument(JsonNode source) {
        if (source == null || !source.isObject()) {
            return null;
        }
        ObjectNode copy = ((ObjectNode) source).deepCopy();
        copy.remove(List.of("id", "sortName", "tagsFlat", "text", "indexedAt"));
        try {
            return json.treeToValue(copy, IndexedResource.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("a document of {} does not read as a resource: {}", alias, e.getMessage());
            return null;
        }
    }

    private static Object sortValueOf(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        return value.asText();
    }

    private Mono<JsonNode> post(String path, String routing, ObjectNode body) {
        return web.post()
                .uri(b -> {
                    b.path(path);
                    if (routing != null) {
                        b.queryParam("routing", routing);
                    }
                    return b.build(alias);
                })
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class) : OpenSearchIndex.<JsonNode>failure(r));
    }

    private static <T> Mono<T> failure(ClientResponse r) {
        return r.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> Mono.error(new OpenSearchException(r.statusCode().value(), text)));
    }
}
