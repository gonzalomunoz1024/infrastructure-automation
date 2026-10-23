package com.iaf.console.cost.pricing.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.cost.pricing.domain.PricingCatalog;
import com.iaf.console.cost.pricing.domain.Rate;
import com.iaf.console.cost.pricing.ports.outbound.PricingSourcePort;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The prices in force: the catalog loaded at start and re-read every interval. When a re-read
 * prices a type differently, every listener hears which types, so the ledger re-prices their
 * resources from that moment; until the first load answers, nothing is priced.
 */
public final class Pricing {

    private static final Logger log = LoggerFactory.getLogger(Pricing.class);

    private final PricingSourcePort source;
    private final Duration interval;
    private final AtomicReference<PricingCatalog> current = new AtomicReference<>();
    private final List<Consumer<Set<String>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Disposable ticking;

    public Pricing(PricingSourcePort source, Duration interval) {
        this.source = source;
        this.interval = interval;
    }

    /** The catalog in force, loading it on first use. */
    public Mono<PricingCatalog> catalog() {
        PricingCatalog c = current.get();
        return c != null ? Mono.just(c) : refresh().thenReturn(0).map(x -> current.get());
    }

    /** Re-reads the source; answers the types whose price changed (empty on the first load). */
    public Mono<Set<String>> refresh() {
        return source.load().map(next -> {
            PricingCatalog before = current.getAndSet(next);
            if (before == null || before.version().equals(next.version())) {
                return Set.<String>of();
            }
            Set<String> changed = next.typesRepricedSince(before);
            if (!changed.isEmpty()) {
                log.info("prices changed (version {} -> {}) for {}", before.version(), next.version(), changed);
                listeners.forEach(l -> l.accept(changed));
            }
            return changed;
        });
    }

    /** One resource's monthly rate under the catalog in force. */
    public Mono<Rate> price(String type, Map<String, ?> desired, Map<String, ?> observed) {
        return catalog().map(c -> c.price(type, desired, observed));
    }

    public void onRepriced(Consumer<Set<String>> listener) {
        listeners.add(listener);
    }

    public String sourceKind() {
        return source.kind();
    }

    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        ticking = Flux.interval(Duration.ZERO, interval, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(t -> refresh().onErrorResume(e -> {
                    log.warn("the price lists could not be read: {}", e.toString());
                    return Mono.just(Set.of());
                }), 1)
                .subscribe();
    }

    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }
}
