package com.iaf.console.identity.accesskeys.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.accesskeys.application.AccessKeyCommands;
import com.iaf.console.identity.accesskeys.application.AccessKeyQueries;
import com.iaf.console.identity.accesskeys.application.CreateAccessKey;
import com.iaf.console.identity.accesskeys.domain.AccessKeyCreated;
import com.iaf.console.identity.accesskeys.domain.AccessKeyView;
import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/access-keys}. */
@RestController
public class AccessKeysController {

    private final AccessKeyCommands commands;
    private final AccessKeyQueries queries;

    public AccessKeysController(AccessKeyCommands commands, AccessKeyQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/access-keys")
    public Mono<Page<AccessKeyView>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) String userId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, userId, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/access-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccessKeyCreated> create(Caller caller, @PathVariable String accountId, @RequestBody CreateAccessKey body) {
        return commands.create(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/access-keys/{keyId}")
    public Mono<AccessKeyView> get(Caller caller, @PathVariable String accountId, @PathVariable String keyId) {
        return queries.get(caller, accountId, keyId);
    }

    @DeleteMapping("/v1/accounts/{accountId}/access-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> revoke(Caller caller, @PathVariable String accountId, @PathVariable String keyId) {
        return commands.revoke(caller, accountId, keyId);
    }

    @PostMapping("/v1/accounts/{accountId}/access-keys/{keyId}:rotate")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccessKeyCreated> rotate(Caller caller, @PathVariable String accountId, @PathVariable String keyId) {
        return commands.rotate(caller, accountId, keyId);
    }
}
