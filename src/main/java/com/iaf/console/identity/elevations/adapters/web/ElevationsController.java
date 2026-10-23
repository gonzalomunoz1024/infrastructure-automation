package com.iaf.console.identity.elevations.adapters.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.elevations.application.DecideElevation;
import com.iaf.console.identity.elevations.application.ElevationCommands;
import com.iaf.console.identity.elevations.application.ElevationOpen;
import com.iaf.console.identity.elevations.application.ElevationQueries;
import com.iaf.console.identity.elevations.application.OpenBreakGlass;
import com.iaf.console.identity.elevations.application.RequestElevation;
import com.iaf.console.identity.elevations.application.RevokeElevation;
import com.iaf.console.identity.elevations.domain.Elevation;
import com.iaf.console.identity.elevations.domain.ElevationRules;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * {@code /v1/accounts/{accountId}/elevations}: a person asks for a role for a while, a rule or an
 * admin decides, and either may end it; {@code /v1/accounts/{accountId}/break-glass} gives support
 * or emergency access (internal only); {@code /v1/elevation-rules} says what is approved at once.
 */
@RestController
public class ElevationsController {

    /** The rules as served: the pending time as an ISO 8601 duration. */
    public record RulesView(int defaultMinutes, int maxMinutes, String pendingFor, int breakGlassMaxMinutes, List<RuleView> items) {
    }

    public record RuleView(String name, List<String> roles, List<String> requesterRoles, int maxMinutes) {
    }

    private final ElevationCommands commands;
    private final ElevationQueries queries;

    public ElevationsController(ElevationCommands commands, ElevationQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/elevation-rules")
    public Mono<RulesView> rules(Caller caller) {
        ElevationRules rules = queries.rules();
        return Mono.just(new RulesView(rules.defaultMinutes(), rules.maxMinutes(), rules.pendingFor().toString(), rules.breakGlassMaxMinutes(),
                rules.autoApproval().stream().map(r -> new RuleView(r.name(), r.roles(), r.requesterRoles(), r.maxMinutes())).toList()));
    }

    @GetMapping("/v1/accounts/{accountId}/elevations")
    public Mono<Page<Elevation>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String userId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, status, kind, userId, Paging.limit(limit), after);
    }

    /** 201 with the elevation, active or pending; 409 with the open one when the person has this role asked for or held already. */
    @PostMapping("/v1/accounts/{accountId}/elevations")
    public Mono<ResponseEntity<Elevation>> request(Caller caller, @PathVariable String accountId, @RequestBody RequestElevation body) {
        return created(commands.request(caller, accountId, body));
    }

    @GetMapping("/v1/accounts/{accountId}/elevations/{elevationId}")
    public Mono<Elevation> get(Caller caller, @PathVariable String accountId, @PathVariable String elevationId) {
        return queries.get(caller, accountId, elevationId);
    }

    @PostMapping("/v1/accounts/{accountId}/elevations/{elevationId}:decide")
    public Mono<Elevation> decide(Caller caller, @PathVariable String accountId, @PathVariable String elevationId, @RequestBody DecideElevation body) {
        return commands.decide(caller, accountId, elevationId, body);
    }

    @PostMapping("/v1/accounts/{accountId}/elevations/{elevationId}:revoke")
    public Mono<Elevation> revoke(Caller caller, @PathVariable String accountId, @PathVariable String elevationId,
            @RequestBody(required = false) RevokeElevation body) {
        return commands.revoke(caller, accountId, elevationId, body);
    }

    /** Internal only: 201 with the access, active; 409 with the open one when the same access is open. */
    @PostMapping("/v1/accounts/{accountId}/break-glass")
    public Mono<ResponseEntity<Elevation>> breakGlass(Caller caller, @PathVariable String accountId, @RequestBody OpenBreakGlass body) {
        return created(commands.breakGlass(caller, accountId, body));
    }

    private static Mono<ResponseEntity<Elevation>> created(Mono<Elevation> made) {
        return made.map(e -> ResponseEntity.status(HttpStatus.CREATED).body(e))
                .onErrorResume(ElevationOpen.class, e -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(e.existing())));
    }
}
