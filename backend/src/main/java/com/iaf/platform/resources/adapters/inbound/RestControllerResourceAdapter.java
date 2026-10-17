package com.iaf.platform.resources.adapters.inbound;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.resources.application.ResourceCommandHandler;
import com.iaf.platform.resources.application.ResourceQueryHandler;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The registry's fixed endpoints. The generated reads ({@code GET /api/v1/<plural>} and
 * {@code /api/v1/<plural>/{id}}) are matched dynamically by the catalog gateway, which consults
 * {@link ResourceQueries#types()} on every request.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerResourceAdapter implements ResourceCommands, ResourceQueries {

    private final ResourceCommandHandler commands;
    private final ResourceQueryHandler queries;

    @Override
    @GetMapping("/resource-types")
    public Flux<ResourceTypeDefinition> types() {
        return queries.types();
    }

    @Override
    @GetMapping("/resource-types/{name}")
    public Mono<ResourceTypeDefinition> type(@PathVariable String name) {
        return queries.type(name).switchIfEmpty(Mono.error(() -> new NotFoundException("resource type", name)));
    }

    @Override
    @GetMapping("/resources")
    public Flux<Resource> list(@RequestParam(required = false) String type, @RequestParam(required = false) String owner,
            @RequestParam(required = false) String status) {
        return queries.list(type, owner, status);
    }

    @Override
    @GetMapping("/resources/{id}")
    public Mono<Resource> get(@PathVariable String id) {
        return queries.get(id).switchIfEmpty(Mono.error(() -> new NotFoundException("resource", id)));
    }

    @Override
    public Mono<Resource> use(String type, String id, Identity requester, boolean checkAccess) {
        return queries.use(type, id, requester, checkAccess);
    }

    @Override
    public Mono<Resource> use(String type, String id, Identity requester, boolean checkAccess, String waitUntil) {
        return queries.use(type, id, requester, checkAccess, waitUntil);
    }

    @Override
    @GetMapping("/resources/{id}/lineage")
    public Mono<Lineage> lineage(@PathVariable String id) {
        return queries.lineage(id);
    }

    @Override
    public Mono<Resource> register(RegisterResource command) {
        return commands.register(command);
    }

    @Override
    @PostMapping("/resources/{id}:retire")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> retire(@PathVariable String id) {
        return commands.retire(id);
    }
}
