package com.iaf.console.identity.principals.adapters.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.application.ResolveRequest;
import com.iaf.console.identity.principals.domain.Resolution;

import reactor.core.publisher.Mono;

/** {@code POST /v1/principals:resolve}. */
@RestController
public class PrincipalsController {

    private final PrincipalCommands commands;

    public PrincipalsController(PrincipalCommands commands) {
        this.commands = commands;
    }

    @PostMapping("/v1/principals:resolve")
    public Mono<Resolution> resolve(Caller caller, @RequestBody ResolveRequest body) {
        return commands.resolve(caller, body);
    }
}
