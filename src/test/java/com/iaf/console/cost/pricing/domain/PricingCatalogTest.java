package com.iaf.console.cost.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.cost.pricing.adapters.bundled.BundledPricing;

/** Pricing from the bundled copy of the hub's list prices and bindings. */
class PricingCatalogTest {

    private final PricingCatalog catalog = BundledPricing.standard().load().block();

    @Test
    void aResourceIsPricedByTheValueItsBindingNames() {
        Rate rate = catalog.price("vm", Map.of("cpu", 4), Map.of());
        assertThat(rate.priced()).isTrue();
        assertThat(rate.monthlyMicros()).isEqualTo(Micros.of(77.0));
        assertThat(rate.pricedFrom()).isEqualTo(Rate.DESIRED);
        assertThat(catalog.price("vm", Map.of("cpu", "4.0"), Map.of()).monthlyMicros()).isEqualTo(Micros.of(77.0));
    }

    @Test
    void whatTheProviderObservedWinsOverWhatWasAskedFor() {
        Rate rate = catalog.price("vm", Map.of("cpu", 4), Map.of("cpu", 8));
        assertThat(rate.monthlyMicros()).isEqualTo(Micros.of(154.0));
        assertThat(rate.pricedFrom()).isEqualTo(Rate.OBSERVED);
    }

    @Test
    void noBindingOrNoPriceIsUnpricedAtZero() {
        assertThat(catalog.price("bucket", Map.of(), Map.of()).priced()).isFalse();
        Rate odd = catalog.price("vm", Map.of("cpu", 3), Map.of());
        assertThat(odd.priced()).isFalse();
        assertThat(odd.monthlyMicros()).isZero();
        assertThat(odd.rule().value()).isEqualTo(3);
    }

    @Test
    void anEstimateSaysWhatItCountsInOneLine() {
        Estimate e = Estimate.of(catalog, "vm", Map.of("cpu", 4), 3);
        assertThat(e.monthly()).isEqualTo(231.0);
        assertThat(e.unitMonthly()).isEqualTo(77.0);
        assertThat(e.detail()).isEqualTo("3 × cpu 4 at $77.00 a month = $231.00 a month (list prices)");
        assertThat(Estimate.of(catalog, "bucket", Map.of(), 1).detail()).startsWith("No list price for bucket");
    }

    @Test
    void aChangedPriceNamesTheTypesItReprices() {
        PricingCatalog cheaper = new PricingCatalog(catalog.lists().stream().map(l -> new PriceList(l.name(), l.title(), l.currency(), "v2",
                l.prices().stream().map(p -> p.action().equals("vm.create")
                        ? Price.perValue(p.provider(), p.action(), p.by(), Map.of("4", new java.math.BigDecimal("70")))
                        : p).toList())).toList(), catalog.bindings(), "v2");
        assertThat(cheaper.typesRepricedSince(catalog)).containsExactly("vm");
    }
}
