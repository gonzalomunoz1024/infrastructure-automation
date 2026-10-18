package com.iaf.platform.runs.application;

import java.util.List;
import java.util.Optional;

import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDetail;
import com.iaf.platform.providers.domain.ProviderSummary;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.workflows.domain.ProviderDrafts;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The providers a dry run with drafts sees: {@link #provider} and {@link #all} answer from the
 * drafts (provider files nobody published yet) before the published providers, a draft standing
 * in for the published provider of its name. The registry reads ({@link #list}, {@link #detail},
 * {@link #alerts}, {@link #ownerOf}) stay the published ones. Built per dry run; nothing is stored.
 */
final class DraftProviders implements ProviderQueries {

    private final ProviderQueries published;
    private final List<ProviderDefinition> drafts;

    DraftProviders(ProviderQueries published, List<ProviderDefinition> drafts) {
        this.published = published;
        this.drafts = List.copyOf(drafts);
    }

    List<ProviderDefinition> drafts() {
        return drafts;
    }

    @Override
    public Mono<ProviderDefinition> provider(String name) {
        Optional<ProviderDefinition> draft = ProviderDrafts.named(drafts, name);
        return draft.map(Mono::just).orElseGet(() -> published.provider(name));
    }

    @Override
    public Flux<ProviderDefinition> all() {
        return published.all().collectList().defaultIfEmpty(List.of()).flatMapIterable(list -> ProviderDrafts.over(list, drafts));
    }

    @Override
    public Flux<ProviderSummary> list() {
        return published.list();
    }

    @Override
    public Mono<ProviderDetail> detail(String name) {
        return published.detail(name);
    }

    @Override
    public Flux<ProviderAlert> alerts(String name) {
        return published.alerts(name);
    }

    @Override
    public Optional<String> ownerOf(String name) {
        return published.ownerOf(name);
    }
}
