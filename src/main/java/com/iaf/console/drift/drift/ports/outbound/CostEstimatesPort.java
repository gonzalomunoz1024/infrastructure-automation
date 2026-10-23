package com.iaf.console.drift.drift.ports.outbound;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * What a resource costs a month (WP 7.3): the cost service's estimate from the platform's price
 * lists, or the price list in this service's settings where no cost service is configured.
 */
@GlobalRead("prices are the same for every account")
public interface CostEstimatesPort {

    /** The monthly cost of one resource of the type with these attributes; empty when nothing prices it. */
    Mono<Money> monthly(String type, String region, ObjectNode attributes);
}
