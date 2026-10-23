package com.iaf.console.identity.accessrequests.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.accessrequests.application.AccessRequestCommands;
import com.iaf.console.identity.accessrequests.application.AccessRequestQueries;
import com.iaf.console.identity.accessrequests.application.DecideAccessRequest;
import com.iaf.console.identity.accessrequests.application.OpenAccessRequest;
import com.iaf.console.identity.accessrequests.domain.AccessRequest;
import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/access-requests}, as the console web proposed (beyond the hub's contract, listed in the README). */
@RestController
public class AccessRequestsController {

    private final AccessRequestCommands commands;
    private final AccessRequestQueries queries;

    public AccessRequestsController(AccessRequestCommands commands, AccessRequestQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/access-requests")
    public Mono<Page<AccessRequest>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String scope, @RequestParam(required = false) Integer limit, @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, status, scope, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/access-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<AccessRequest> open(Caller caller, @PathVariable String accountId, @RequestBody OpenAccessRequest body) {
        return commands.open(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/access-requests/{requestId}")
    public Mono<AccessRequest> get(Caller caller, @PathVariable String accountId, @PathVariable String requestId) {
        return queries.get(caller, accountId, requestId);
    }

    @PostMapping("/v1/accounts/{accountId}/access-requests/{requestId}:decide")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<AccessRequest> decide(Caller caller, @PathVariable String accountId, @PathVariable String requestId, @RequestBody DecideAccessRequest body) {
        return commands.decide(caller, accountId, requestId, body);
    }
}
