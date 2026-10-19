package com.iaf.platform.providers.ports.inbound;

import java.util.Optional;

import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDetail;
import com.iaf.platform.providers.domain.ProviderSummary;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Read side of the providers slice, used by the checks, the interpreter and the registry. */
public interface ProviderQueries {

    /**
     * Who is about to call a provider: the job, its environment and whether it is a dry run. In
     * the Reactor context of {@link #provider(String)} it lets an active provider release route
     * the lookup to its candidate version; without it the lookup returns the current version.
     */
    record Use(String jobId, String environment, boolean dryRun) {

        public static Context with(Context context, Use use) {
            return use == null ? context : context.put(Use.class, use);
        }

        public static Use of(ContextView context) {
            return context.getOrDefault(Use.class, null);
        }
    }

    /** Empty Mono when unknown. The current version, or the one a provider release routes the {@link Use} in the context to. */
    Mono<ProviderDefinition> provider(String name);

    Flux<ProviderDefinition> all();

    /** Every provider with its health over the last day ({@code GET /providers}). */
    Flux<ProviderSummary> list();

    /** One provider in full: definition, file, actions, call statistics and change requests ({@code GET /providers/{name}}); 404 when unknown. */
    Mono<ProviderDetail> detail(String name);

    /** The provider's open alerts ({@code GET /providers/{name}/alerts}); 404 when unknown. */
    Flux<ProviderAlert> alerts(String name);

    /**
     * The owner team of the published provider (its current stored version), which decides who may
     * change it; empty when no provider has that name or it names no owner. A store read: call it
     * where blocking is allowed, as a command handler's bounded elastic section is.
     */
    Optional<String> ownerOf(String name);

    /**
     * The version of the provider that serves calls now (its current stored version; a version a
     * provider release still rolls out is not current until it is promoted); empty when no provider
     * has that name. A store read: call it where blocking is allowed, as {@link #ownerOf} is.
     */
    Optional<String> currentVersion(String name);
}
