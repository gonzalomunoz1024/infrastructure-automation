package com.iaf.console.identity.authz.adapters.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.console.identity.authz.application.AuthorizeRequest;
import com.iaf.console.identity.authz.application.AuthorizeResponse;
import com.iaf.console.identity.authz.application.Authorizer;
import com.iaf.console.identity.common.auth.Caller;

import reactor.core.publisher.Mono;

/** {@code POST /v1/authorize}. */
@RestController
public class AuthorizeController {

    private final Authorizer authorizer;

    public AuthorizeController(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @PostMapping("/v1/authorize")
    public Mono<AuthorizeResponse> authorize(Caller caller, @RequestBody AuthorizeRequest body) {
        return authorizer.authorize(caller, body);
    }
}
