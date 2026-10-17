package com.iaf.platform.providers.ports.outbound;

import com.iaf.platform.providers.domain.ProviderDefinition.Auth;

import reactor.core.publisher.Mono;

/**
 * The wizard's two touches of the network: fetching an OpenAPI document and probing an endpoint.
 * The demo adapter simulates {@code example.com} hosts (there is no network in the demo) and tries
 * everything else for real.
 */
public interface HttpProbePort {

    record ProbeResult(boolean ok, long durationMs, String message) {
    }

    /** GET the document at a URL, following redirects, within 10 seconds. */
    Mono<String> fetch(String url);

    /** HEAD then GET an endpoint with the provider's auth, within 5 seconds each. Never errors: the result says why. */
    Mono<ProbeResult> probe(String url, Auth auth);
}
