package com.iaf.console.identity.principals.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.principals.application.AddUser;
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.application.PrincipalQueries;
import com.iaf.console.identity.principals.application.UpdateUser;
import com.iaf.console.identity.principals.domain.AccountUser;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/users}. */
@RestController
public class UsersController {

    private final PrincipalCommands commands;
    private final PrincipalQueries queries;

    public UsersController(PrincipalCommands commands, PrincipalQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/users")
    public Mono<Page<AccountUser>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, q, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccountUser> add(Caller caller, @PathVariable String accountId, @RequestBody AddUser body) {
        return commands.addUser(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/users/{userId}")
    public Mono<AccountUser> get(Caller caller, @PathVariable String accountId, @PathVariable String userId) {
        return queries.get(caller, accountId, userId);
    }

    @PatchMapping("/v1/accounts/{accountId}/users/{userId}")
    public Mono<AccountUser> update(Caller caller, @PathVariable String accountId, @PathVariable String userId, @RequestBody UpdateUser body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return commands.updateUser(caller, accountId, userId, body, Versions.ifMatch(ifMatch));
    }

    @DeleteMapping("/v1/accounts/{accountId}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> remove(Caller caller, @PathVariable String accountId, @PathVariable String userId) {
        return commands.removeUser(caller, accountId, userId);
    }
}
