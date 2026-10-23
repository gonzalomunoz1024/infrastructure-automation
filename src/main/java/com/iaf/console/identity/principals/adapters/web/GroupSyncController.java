package com.iaf.console.identity.principals.adapters.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.principals.application.GroupSync;
import com.iaf.console.identity.principals.application.GroupSyncReport;

import reactor.core.publisher.Mono;

/** {@code POST /internal/v1/group-sync:run}: the nightly identity provider group sync, now (internal principal only). */
@RestController
public class GroupSyncController {

    private final GroupSync sync;

    public GroupSyncController(GroupSync sync) {
        this.sync = sync;
    }

    @PostMapping("/internal/v1/group-sync:run")
    public Mono<GroupSyncReport> run(Caller caller) {
        return sync.run(caller);
    }
}
