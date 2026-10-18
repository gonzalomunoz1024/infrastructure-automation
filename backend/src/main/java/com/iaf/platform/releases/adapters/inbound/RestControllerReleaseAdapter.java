package com.iaf.platform.releases.adapters.inbound;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.releases.application.ReleaseCommandHandler;
import com.iaf.platform.releases.application.ReleaseQueryHandler;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.ports.inbound.ReleaseCommands;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code /api/v1/releases}. POST maps to {@link ReleaseCommands}, GET to {@link ReleaseQueries}
 * ({@code route}, {@code routeProvider} and {@code providerCandidate} are programmatic, for the
 * catalog and the providers slice, not endpoints).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerReleaseAdapter implements ReleaseCommands, ReleaseQueries {

    private final ReleaseCommandHandler commands;
    private final ReleaseQueryHandler queries;

    @Override
    @GetMapping("/releases")
    public Flux<Release> list(@RequestParam(required = false) String workflow, @RequestParam(required = false) String provider,
            @RequestParam(required = false) String kind) {
        return queries.list(workflow, provider, kind);
    }

    @Override
    @GetMapping("/releases/{id}")
    public Mono<Release> get(@PathVariable String id) {
        return queries.get(id);
    }

    @Override
    public Mono<String> route(String workflow, String publishedVersion) {
        return queries.route(workflow, publishedVersion);
    }

    @Override
    public Mono<String> routeProvider(String provider, String jobId, String environment, boolean dryRun) {
        return queries.routeProvider(provider, jobId, environment, dryRun);
    }

    @Override
    public Mono<String> providerCandidate(String provider) {
        return queries.providerCandidate(provider);
    }

    @Override
    @PostMapping("/releases/{id}:promote")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> promote(@PathVariable String id) {
        return commands.promote(id);
    }

    @Override
    @PostMapping("/releases/{id}:pause")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> pause(@PathVariable String id) {
        return commands.pause(id);
    }

    @Override
    @PostMapping("/releases/{id}:resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> resume(@PathVariable String id) {
        return commands.resume(id);
    }

    @Override
    @PostMapping("/releases/{id}:rollback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> rollback(@PathVariable String id) {
        return commands.rollback(id);
    }

    @Override
    @PostMapping("/releases/{id}:evaluate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> evaluate(@PathVariable String id) {
        return commands.evaluate(id);
    }
}
