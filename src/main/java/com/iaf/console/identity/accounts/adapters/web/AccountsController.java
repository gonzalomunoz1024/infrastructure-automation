package com.iaf.console.identity.accounts.adapters.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.accounts.application.AccountCommands;
import com.iaf.console.identity.accounts.application.AccountQueries;
import com.iaf.console.identity.accounts.application.CreateAccount;
import com.iaf.console.identity.accounts.application.UpdateAccount;
import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.domain.PlatformCredentials;
import com.iaf.console.identity.common.Paging;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts}: the contract's Accounts tag and the internal platform-credentials read. */
@RestController
public class AccountsController {

    private final AccountCommands commands;
    private final AccountQueries queries;

    public AccountsController(AccountCommands commands, AccountQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts")
    public Mono<Page<Account>> list(Caller caller, @RequestParam(required = false) AccountStatus status, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return queries.list(caller, status, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Account> create(Caller caller, @RequestBody CreateAccount body) {
        return commands.create(caller, body);
    }

    @GetMapping("/v1/accounts/{accountId}")
    public Mono<Account> get(Caller caller, @PathVariable String accountId) {
        return queries.get(caller, accountId);
    }

    @PatchMapping("/v1/accounts/{accountId}")
    public Mono<Account> update(Caller caller, @PathVariable String accountId, @RequestBody UpdateAccount body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return commands.update(caller, accountId, body, Versions.ifMatch(ifMatch));
    }

    @GetMapping("/v1/accounts/{accountId}/platform-credentials")
    public Mono<PlatformCredentials> platformCredentials(Caller caller, @PathVariable String accountId) {
        return queries.platformCredentials(caller, accountId);
    }
}
