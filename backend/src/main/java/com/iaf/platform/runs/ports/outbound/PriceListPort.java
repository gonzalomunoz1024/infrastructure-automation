package com.iaf.platform.runs.ports.outbound;

import com.iaf.platform.runs.domain.PriceList;

import reactor.core.publisher.Flux;

/**
 * The price lists ({@code kind: PriceList} files) a preview prices create calls with when the
 * action's plan answers no price itself. The adapter reads them from the content repo.
 */
public interface PriceListPort {

    /** Every list, by name; the first list that prices a call wins. */
    Flux<PriceList> priceLists();
}
