package com.iaf.platform.providers.adapters.inbound;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.ports.CredentialsPort.CredentialInfo;
import com.iaf.platform.providers.application.CredentialsHandler;
import com.iaf.platform.providers.ports.inbound.CredentialsCommands;
import com.iaf.platform.providers.ports.inbound.CredentialsQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/** {@code GET/POST /api/v1/credentials}: names, types and paths only; a value never comes back. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerCredentialsAdapter implements CredentialsCommands, CredentialsQueries {

    private final CredentialsHandler handler;

    @Override
    @GetMapping("/credentials")
    public Mono<List<CredentialInfo>> credentials() {
        return handler.credentials();
    }

    @Override
    @PostMapping("/credentials")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> storeCredential(@RequestBody StoreCredential command) {
        return handler.storeCredential(command);
    }
}
