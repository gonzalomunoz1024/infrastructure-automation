package com.iaf.console.state.inventory.adapters.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.iaf.console.state.boot.StateProperties;
import com.iaf.console.state.inventory.application.Relationships;
import com.iaf.console.state.inventory.domain.BlastRadius;
import com.iaf.console.state.inventory.domain.Lineage;
import com.iaf.console.state.inventory.domain.ResourceGraph;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.TagRules;

import reactor.core.publisher.Mono;

/**
 * The relationship reads: a resource's lineage, the blast radius of taking resources away (one, or
 * up to a hundred for a bulk action) and the resource map of an account. An internal caller may
 * map several accounts at once and sees other accounts' resources; an account's caller sees its
 * own and gets the rest as counts.
 */
@RestController
public final class RelationshipController {

    /** The nodes a map draws when the caller names no limit. */
    static final int DEFAULT_MAP_NODES = 500;
    /** The most accounts one map covers. */
    static final int MAX_MAP_ACCOUNTS = 20;

    private final Relationships relationships;
    private final String defaultRegion;

    public RelationshipController(Relationships relationships, StateProperties properties) {
        this.relationships = relationships;
        this.defaultRegion = properties.region();
    }

    @GetMapping("/v1/wfrn/{wfrn}/lineage")
    public Mono<Lineage> lineage(ServerWebExchange exchange, @PathVariable String wfrn,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) Integer limit) {
        ConsolePrincipal principal = ResourceController.principal(exchange);
        ResourceIdentity id = ResourceIdentity.of(wfrn);
        AccountScope scope = principal.scope(id.accountId(), id.region());
        return relationships.lineage(scope, wfrn, depth == null ? Relationships.MAX_DEPTH : depth,
                limit == null ? Relationships.DEFAULT_NODES : limit, principal.internal());
    }

    @GetMapping("/v1/wfrn/{wfrn}/blast-radius")
    public Mono<BlastRadius> blastRadius(ServerWebExchange exchange, @PathVariable String wfrn, @RequestParam(required = false) Integer depth) {
        return blastRadius(exchange, List.of(wfrn), depth);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BlastRadiusRequest(List<String> wfrns, Integer depth) {
    }

    @PostMapping("/v1/resources:blast-radius")
    public Mono<BlastRadius> blastRadius(ServerWebExchange exchange, @RequestBody BlastRadiusRequest body) {
        if (body.wfrns() == null || body.wfrns().isEmpty() || body.wfrns().size() > Relationships.MAX_ROOTS) {
            throw new InvalidInputException("name 1 to " + Relationships.MAX_ROOTS + " resources",
                    List.of(Problem.error("size", "/wfrns", "1 to " + Relationships.MAX_ROOTS + " wfrns of one account and region")));
        }
        return blastRadius(exchange, body.wfrns(), body.depth());
    }

    private Mono<BlastRadius> blastRadius(ServerWebExchange exchange, List<String> wfrns, Integer depth) {
        ConsolePrincipal principal = ResourceController.principal(exchange);
        ResourceIdentity first = ResourceIdentity.of(wfrns.get(0));
        AccountScope scope = principal.scope(first.accountId(), first.region());
        return relationships.blastRadius(scope, wfrns, depth == null ? Relationships.MAX_DEPTH : depth, principal.internal());
    }

    @GetMapping("/v1/graph")
    public Mono<ResourceGraph> graph(ServerWebExchange exchange,
            @RequestParam(required = false) List<String> accountId,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean drift,
            @RequestParam(required = false) Integer limit) {
        ConsolePrincipal principal = ResourceController.principal(exchange);
        String r = region != null ? region : principal.region() != null ? principal.region() : defaultRegion;
        List<String> accounts = accountId == null ? List.of() : accountId.stream().flatMap(a -> List.of(a.split(",")).stream())
                .map(String::trim).filter(a -> !a.isEmpty()).distinct().toList();
        if (accounts.size() > MAX_MAP_ACCOUNTS) {
            throw new InvalidInputException("a map covers at most " + MAX_MAP_ACCOUNTS + " accounts",
                    List.of(Problem.error("size", "/accountId", "at most " + MAX_MAP_ACCOUNTS + " accounts")));
        }
        List<AccountScope> scopes = new ArrayList<>();
        if (accounts.isEmpty()) {
            scopes.add(principal.scope(null, r));
        } else {
            accounts.forEach(a -> scopes.add(principal.scope(a, r)));
        }
        ResourceState s = null;
        if (state != null) {
            s = ResourceState.of(state);
            if (s == null || s == ResourceState.DELETED) {
                throw new InvalidInputException("unknown state", List.of(Problem.error("enum", "/state", "one of: pending, active, updating, deleting, failed")));
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        exchange.getRequest().getQueryParams().forEach((k, v) -> {
            if (k.startsWith("tag.") && !v.isEmpty()) {
                String tag = k.substring(4);
                if (!TagRules.NAME.matcher(tag).matches()) {
                    throw new InvalidInputException("bad tag filter", List.of(Problem.error("tag.name", "/" + k, "a tag name is [a-z0-9-]{1,32}")));
                }
                tags.put(tag, v.get(0));
            }
        });
        ResourceQuery query = ResourceQuery.all().withType(type).withState(s).withNamePrefix(name).withTags(tags).withDrift(drift);
        return relationships.graph(scopes, query, limit == null ? DEFAULT_MAP_NODES : limit, principal.internal());
    }
}
