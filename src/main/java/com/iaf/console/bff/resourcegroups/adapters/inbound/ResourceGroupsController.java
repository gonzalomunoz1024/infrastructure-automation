package com.iaf.console.bff.resourcegroups.adapters.inbound;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.resourcegroups.application.ResourceGroups;
import com.iaf.console.bff.resourcegroups.domain.GroupChanges;
import com.iaf.console.bff.resourcegroups.domain.NewGroup;
import com.iaf.console.bff.resourcegroups.domain.ResourceGroup;
import com.iaf.console.bff.resources.domain.ResourceView;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * {@code /console/v1/resource-groups}: the account's resource groups in the session's region, one
 * group with its {@code ETag} (the version), a create ({@code Idempotency-Key}), a change
 * ({@code If-Match}), a delete, and the resources a group's query matches.
 */
@RestController
public class ResourceGroupsController {

    private static final String GROUPS = "/console/v1/resource-groups";
    private static final String GROUP = GROUPS + "/{groupId}";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    /** Set on a create that an earlier one with the same Idempotency-Key answered, as the state service does. */
    static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";

    private final ResourceGroups groups;

    public ResourceGroupsController(ResourceGroups groups) {
        this.groups = groups;
    }

    @GetMapping(path = GROUPS, produces = "application/json")
    public Mono<Page<ResourceGroup>> list(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(groups::list).map(Page::last);
    }

    @PostMapping(path = GROUPS, consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<ResourceGroup>> create(@RequestBody(required = false) NewGroup body,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> groups.create(session, body == null ? NewGroup.empty() : body, idempotencyKey))
                .map(created -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity.created(URI.create(GROUPS + "/" + created.group().groupId()))
                            .eTag(etag(created.group()));
                    if (created.replayed()) {
                        builder = builder.header(IDEMPOTENT_REPLAY, "true");
                    }
                    return builder.body(created.group());
                });
    }

    @GetMapping(path = GROUP, produces = "application/json")
    public Mono<ResponseEntity<ResourceGroup>> get(@PathVariable("groupId") String groupId,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> groups.get(session, groupId)).map(group -> {
            String etag = etag(group);
            if (matches(ifNoneMatch, etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).<ResourceGroup>build();
            }
            return ResponseEntity.ok().eTag(etag).header(HttpHeaders.CACHE_CONTROL, "private, no-cache").body(group);
        });
    }

    @PatchMapping(path = GROUP, consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<ResourceGroup>> update(@PathVariable("groupId") String groupId, @RequestBody(required = false) GroupChanges body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> groups.update(session, groupId, body == null ? GroupChanges.none() : body, ifMatch))
                .map(group -> ResponseEntity.ok().eTag(etag(group)).body(group));
    }

    @DeleteMapping(path = GROUP)
    public Mono<ResponseEntity<Void>> delete(@PathVariable("groupId") String groupId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> groups.delete(session, groupId))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @GetMapping(path = GROUP + "/resources", produces = "application/json")
    public Mono<Page<ResourceView>> resources(@PathVariable("groupId") String groupId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> groups.resources(session, groupId, limit, after));
    }

    /** A group's ETag: its version, quoted. */
    static String etag(ResourceGroup group) {
        return "\"" + group.version() + "\"";
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.strip();
            if (c.equals("*") || c.equals(etag) || c.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }
}
