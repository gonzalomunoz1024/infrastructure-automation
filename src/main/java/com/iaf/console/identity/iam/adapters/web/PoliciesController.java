package com.iaf.console.identity.iam.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.PolicyCommands;
import com.iaf.console.identity.iam.application.PolicyInput;
import com.iaf.console.identity.iam.application.PolicyQueries;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/policies}. */
@RestController
public class PoliciesController {

    private final PolicyCommands commands;
    private final PolicyQueries queries;

    public PoliciesController(PolicyCommands commands, PolicyQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/policies")
    public Mono<Page<Policy>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Policy> create(Caller caller, @PathVariable String accountId, @RequestBody PolicyInput body) {
        return commands.create(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/policies/{policyId}")
    public Mono<Policy> get(Caller caller, @PathVariable String accountId, @PathVariable String policyId) {
        return queries.get(caller, accountId, policyId);
    }

    @PutMapping("/v1/accounts/{accountId}/policies/{policyId}")
    public Mono<Policy> replace(Caller caller, @PathVariable String accountId, @PathVariable String policyId, @RequestBody PolicyInput body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return commands.replace(caller, accountId, policyId, body, Versions.ifMatch(ifMatch));
    }

    @DeleteMapping("/v1/accounts/{accountId}/policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(Caller caller, @PathVariable String accountId, @PathVariable String policyId) {
        return commands.delete(caller, accountId, policyId);
    }
}
