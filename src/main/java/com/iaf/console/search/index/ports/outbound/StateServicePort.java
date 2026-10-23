package com.iaf.console.search.index.ports.outbound;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.domain.StateResource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The state service, the owner of every resource: the whole resource when an event is not enough, and its lists for a rebuild. */
public interface StateServicePort {

    /** The resource by wfrn: empty when the state service has no such resource, a tombstone when it answers 410. */
    Mono<StateResource> find(AccountScope scope, String wfrn);

    /** Every resource a listing selects, page after page, oldest change first. */
    Flux<StateResource> list(AccountScope scope, StateListing listing);
}
