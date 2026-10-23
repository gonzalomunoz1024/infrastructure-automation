package com.iaf.console.cost.pricing.ports.outbound;

import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** Where the price lists and the types' bindings come from: the platform (P-22) or the bundled copy. */
@GlobalRead("prices belong to no account")
public interface PricingSourcePort {

    /** Every price list and binding as of now. */
    Mono<PricingCatalog> load();

    /** {@code platform} or {@code bundled}, for the status. */
    String kind();
}
