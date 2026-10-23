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
import com.iaf.console.identity.iam.application.GroupCommands;
import com.iaf.console.identity.iam.application.GroupInput;
import com.iaf.console.identity.iam.application.GroupQueries;
import com.iaf.console.identity.iam.domain.GroupView;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** {@code /v1/accounts/{accountId}/groups}. */
@RestController
public class GroupsController {

    private final GroupCommands commands;
    private final GroupQueries queries;

    public GroupsController(GroupCommands commands, GroupQueries queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/v1/accounts/{accountId}/groups")
    public Mono<Page<GroupView>> list(Caller caller, @PathVariable String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        return queries.list(caller, accountId, Paging.limit(limit), after);
    }

    @PostMapping("/v1/accounts/{accountId}/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<GroupView> create(Caller caller, @PathVariable String accountId, @RequestBody GroupInput body) {
        return commands.create(caller, accountId, body);
    }

    @GetMapping("/v1/accounts/{accountId}/groups/{groupId}")
    public Mono<GroupView> get(Caller caller, @PathVariable String accountId, @PathVariable String groupId) {
        return queries.get(caller, accountId, groupId);
    }

    @PatchMapping("/v1/accounts/{accountId}/groups/{groupId}")
    public Mono<GroupView> update(Caller caller, @PathVariable String accountId, @PathVariable String groupId, @RequestBody GroupInput body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return commands.update(caller, accountId, groupId, body, Versions.ifMatch(ifMatch));
    }

    @DeleteMapping("/v1/accounts/{accountId}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(Caller caller, @PathVariable String accountId, @PathVariable String groupId) {
        return commands.delete(caller, accountId, groupId);
    }
}
