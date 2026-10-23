package com.iaf.console.identity.iam.adapters.web;

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
import com.iaf.console.identity.iam.application.RoleCommands;
import com.iaf.console.identity.iam.application.RoleInput;
import com.iaf.console.identity.iam.application.RoleQueries;
import com.iaf.console.identity.iam.domain.Role;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/roles}. */
@RestController
public class RolesController {

    private final RoleCommands commands;
    private final RoleQueries queries;

    public RolesController(RoleCommands commands, RoleQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/roles")
    public Mono<Page<Role>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Role> create(Caller caller, @PathVariable String accountId, @RequestBody RoleInput body) {
        return commands.create(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/roles/{roleId}")
    public Mono<Role> get(Caller caller, @PathVariable String accountId, @PathVariable String roleId) {
        return queries.get(caller, accountId, roleId);
    }

    @PatchMapping("/v1/accounts/{accountId}/roles/{roleId}")
    public Mono<Role> update(Caller caller, @PathVariable String accountId, @PathVariable String roleId, @RequestBody RoleInput body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return commands.update(caller, accountId, roleId, body, Versions.ifMatch(ifMatch));
    }

    @DeleteMapping("/v1/accounts/{accountId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(Caller caller, @PathVariable String accountId, @PathVariable String roleId) {
        return commands.delete(caller, accountId, roleId);
    }
}
