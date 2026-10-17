package com.iaf.platform.identity.adapters.inbound;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.identity.application.IdentityCommandHandler;
import com.iaf.platform.identity.application.IdentityQueryHandler;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.inbound.IdentityCommands;
import com.iaf.platform.identity.ports.inbound.IdentityQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerIdentityAdapter implements IdentityCommands, IdentityQueries {

    private final IdentityCommandHandler commands;
    private final IdentityQueryHandler queries;

    @Override
    @GetMapping("/me")
    public Mono<Identity> me() {
        return queries.me();
    }

    @Override
    @GetMapping("/service-accounts")
    public Mono<List<ServiceAccount>> serviceAccounts() {
        return queries.serviceAccounts();
    }

    @Override
    @PostMapping("/service-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ServiceAccountCreated> createServiceAccount(@RequestBody CreateServiceAccount command) {
        return commands.createServiceAccount(command);
    }
}
