package com.iaf.console.bff.resources.adapters.inbound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.jobs.application.OnBehalfOf;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.domain.ActivityEntry;
import com.iaf.console.bff.resources.domain.BatchAccepted;
import com.iaf.console.bff.resources.domain.BatchInput;
import com.iaf.console.bff.resources.domain.BatchPreview;
import com.iaf.console.bff.resources.domain.BlastRadius;
import com.iaf.console.bff.resources.domain.CloneDraft;
import com.iaf.console.bff.resources.domain.CommandAccepted;
import com.iaf.console.bff.resources.domain.CommandInput;
import com.iaf.console.bff.resources.domain.LineageView;
import com.iaf.console.bff.resources.domain.PreviewView;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * {@code /console/v1/r/{type}}: the account's resources of a type, one resource, its activity,
 * lineage and tags, and the commands (create, update, a verb, their previews, a batch). The Drift
 * tab ({@code /drift}) and the Monitoring tab ({@code /metrics}, {@code /alarms}) are the drift
 * slice's, through the drift service.
 * The wfrn travels percent-encoded in the path; a type name is {@code [a-z0-9-]}, so {@code :preview}
 * and {@code :batch} on the collection never read as a type.
 */
@RestController
public class ResourcesController {

    private static final String TYPE = "{type:[a-z0-9-]+}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Resources resources;

    public ResourcesController(Resources resources) {
        this.resources = resources;
    }

    @GetMapping(path = "/console/v1/r/" + TYPE, produces = "application/json")
    public Mono<Page<ResourceView>> list(@PathVariable("type") String type,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "drift", required = false) Boolean drift,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        Map<String, String> tags = new LinkedHashMap<>();
        exchange.getRequest().getQueryParams().forEach((name, values) -> {
            if (name.startsWith("tag.") && name.length() > 4 && !values.isEmpty()) {
                tags.put(name.substring(4), values.get(0));
            }
        });
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.list(session, type, new Resources.ListFilters(q, state, tags, drift, sort, limit, after)));
    }

    @PostMapping(path = "/console/v1/r/" + TYPE, consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<CommandAccepted>> create(@PathVariable("type") String type, @RequestBody(required = false) CommandInput body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.create(session, type, body == null ? CommandInput.empty() : body, requestId(exchange), idempotencyKey))
                .map(accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted));
    }

    @PostMapping(path = "/console/v1/r/" + TYPE + ":preview", consumes = "application/json", produces = "application/json")
    public Mono<PreviewView> previewCreate(@PathVariable("type") String type, @RequestBody(required = false) CommandInput body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.previewCreate(session, type, body == null ? CommandInput.empty() : body, requestId(exchange)));
    }

    @PostMapping(path = "/console/v1/r/" + TYPE + ":batch", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<BatchAccepted>> batch(@PathVariable("type") String type, @RequestBody BatchInput body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.batch(session, type, body, requestId(exchange), idempotencyKey))
                .map(accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted));
    }

    @PostMapping(path = "/console/v1/r/" + TYPE + ":batch:preview", consumes = "application/json", produces = "application/json")
    public Mono<BatchPreview> previewBatch(@PathVariable("type") String type, @RequestBody BatchInput body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> resources.previewBatch(session, type, body));
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}", produces = "application/json")
    public Mono<ResponseEntity<ResourceView>> get(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> resources.get(session, type, wfrn, ifNoneMatch)).map(detail -> {
            ResponseEntity.BodyBuilder builder = detail.notModified() ? ResponseEntity.status(HttpStatus.NOT_MODIFIED) : ResponseEntity.ok();
            if (detail.etag() != null) {
                builder = builder.eTag(detail.etag());
            }
            builder = builder.header(HttpHeaders.CACHE_CONTROL, "private, no-cache");
            return detail.notModified() ? builder.<ResourceView>build() : builder.body(detail.view());
        });
    }

    @PatchMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<CommandAccepted>> update(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestBody(required = false) CommandInput body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.update(session, type, wfrn, body == null ? CommandInput.empty() : body, ifMatch, requestId(exchange), idempotencyKey))
                .map(accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted));
    }

    @PostMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}:{verb}", produces = "application/json")
    public Mono<ResponseEntity<CommandAccepted>> run(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @PathVariable("verb") String verb, @RequestBody(required = false) CommandInput body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.run(session, type, wfrn, verb, body == null ? CommandInput.empty() : body, requestId(exchange), idempotencyKey))
                .map(accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted));
    }

    @PostMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}:{verb}:preview", produces = "application/json")
    public Mono<PreviewView> previewVerb(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn, @PathVariable("verb") String verb,
            @RequestBody(required = false) CommandInput body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.previewVerb(session, type, wfrn, verb, body == null ? CommandInput.empty() : body, requestId(exchange)));
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/activity", produces = "application/json")
    public Mono<Page<ActivityEntry>> activity(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> resources.activity(session, type, wfrn, limit, after));
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/lineage", produces = "application/json")
    public Mono<LineageView> lineage(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> resources.lineage(session, type, wfrn));
    }

    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/blast-radius", produces = "application/json")
    public Mono<BlastRadius> blastRadius(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestParam(name = "depth", required = false) Integer depth, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> resources.blastRadius(session, type, wfrn, depth));
    }

    /** Launch more like this: the type's create form filled from the resource, for this account or another of the person's ({@code account}). */
    @GetMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/clone", produces = "application/json")
    public Mono<CloneDraft> cloneDraft(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestParam(name = "account", required = false) String account, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> resources.cloneDraft(session, type, wfrn, account));
    }

    @PutMapping(path = "/console/v1/r/" + TYPE + "/{wfrn}/tags", consumes = "application/json", produces = "application/json")
    public Mono<Map<String, String>> tags(@PathVariable("type") String type, @PathVariable("wfrn") String wfrn,
            @RequestBody(required = false) Map<String, String> tags,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> resources.setTags(session, type, wfrn, tags == null ? Map.of() : tags, requestId(exchange), idempotencyKey));
    }

    private static String requestId(ServerWebExchange exchange) {
        return OnBehalfOf.requestId(Correlation.of(exchange));
    }

    /** For the routes test: the paths this controller serves, as the contract spells them. */
    public static List<String> contractPaths() {
        return List.of("/console/v1/r/{type}", "/console/v1/r/{type}:preview", "/console/v1/r/{type}:batch", "/console/v1/r/{type}/{wfrn}",
                "/console/v1/r/{type}/{wfrn}:{verb}", "/console/v1/r/{type}/{wfrn}:{verb}:preview", "/console/v1/r/{type}/{wfrn}/activity",
                "/console/v1/r/{type}/{wfrn}/lineage", "/console/v1/r/{type}/{wfrn}/tags");
    }
}
