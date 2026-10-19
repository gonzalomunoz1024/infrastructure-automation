package com.iaf.platform.providers.adapters.inbound;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.providers.application.ProviderCommandHandler;
import com.iaf.platform.providers.application.ProviderQueryHandler;
import com.iaf.platform.providers.domain.ConnectionResult;
import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDetail;
import com.iaf.platform.providers.domain.ProviderSummary;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.domain.TryResult;
import com.iaf.platform.providers.ports.inbound.ProviderCommands;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The Providers section of docs/api.md: GET maps to the query handler, POST and PUT to commands. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerProviderAdapter implements ProviderCommands, ProviderQueries {

    private final ProviderCommandHandler commands;
    private final ProviderQueryHandler queries;

    @Override
    @GetMapping("/providers")
    public Flux<ProviderSummary> list() {
        return queries.list();
    }

    @Override
    @GetMapping("/providers/{name}")
    public Mono<ProviderDetail> detail(@PathVariable String name) {
        return queries.detail(name);
    }

    @Override
    @GetMapping("/providers/{name}/alerts")
    public Flux<ProviderAlert> alerts(@PathVariable String name) {
        return queries.alerts(name);
    }

    @Override
    public Mono<ProviderDefinition> provider(String name) {
        return queries.provider(name);
    }

    @Override
    public Flux<ProviderDefinition> all() {
        return queries.all();
    }

    @Override
    public Optional<String> ownerOf(String name) {
        return queries.ownerOf(name);
    }

    @Override
    public Optional<String> currentVersion(String name) {
        return queries.currentVersion(name);
    }

    @Override
    @PostMapping("/providers:import-spec")
    public Mono<SpecImport> importSpec(@RequestBody ImportSpec command) {
        return commands.importSpec(command);
    }

    @Override
    @PostMapping("/providers:test-connection")
    public Mono<ConnectionResult.Results> testConnection(@RequestBody TestConnection command) {
        return commands.testConnection(command);
    }

    @Override
    @PostMapping("/providers:try")
    public Mono<TryResult> tryAction(@RequestBody TryAction command) {
        return commands.tryAction(command);
    }

    @Override
    @PostMapping("/providers:validate")
    public Mono<ValidationResult> validate(@RequestBody Validate command) {
        return commands.validate(command);
    }

    @Override
    @PostMapping("/providers:publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> publish(@RequestBody Publish command) {
        return commands.publish(command);
    }

    @Override
    @PostMapping("/providers:draft")
    public Mono<ProviderDraft> draft(@RequestBody Draft command) {
        return commands.draft(command);
    }

    @Override
    @PostMapping("/providers/{name}:refresh-spec")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> refreshSpec(@PathVariable String name, @RequestBody(required = false) RefreshSpec command) {
        return commands.refreshSpec(name, command);
    }

    @Override
    @PostMapping("/providers/{name}/overrides")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> overrides(@PathVariable String name, @RequestBody SetOverride command) {
        return commands.overrides(name, command);
    }

    @Override
    @PutMapping("/providers/{name}/slo")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> slo(@PathVariable String name, @RequestBody SetSlo command) {
        return commands.slo(name, command);
    }

    /** Not an endpoint: merging the change request (the workflows slice) publishes the provider. */
    @Override
    public Mono<Void> publishMerged(String changeRequestId) {
        return commands.publishMerged(changeRequestId);
    }
}
