package com.iaf.console.bff.platform.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.session.ports.outbound.RegionsPort;
import com.iaf.console.platform.model.Region;
import com.iaf.console.platform.model.RegionAvailability;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The platform's regions with what each offers (P-07), read with the internal client, cached, and
 * refreshed when the feed says {@code region.changed} or the cache ages out.
 */
public class Regions implements RegionsPort {

    private static final Logger log = LoggerFactory.getLogger(Regions.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    /** A region as the console lists it. */
    public record ConsoleRegion(String name, String title, String status, List<String> environments, List<String> types, List<String> workflows) {
    }

    private final PlatformClients clients;
    private final AtomicReference<Mono<List<ConsoleRegion>>> cached = new AtomicReference<>();

    public Regions(PlatformClients clients) {
        this.clients = clients;
    }

    public Mono<List<ConsoleRegion>> all() {
        Mono<List<ConsoleRegion>> current = cached.get();
        if (current == null) {
            current = load().cache(TTL);
            if (!cached.compareAndSet(null, current)) {
                current = cached.get();
            }
        }
        return current;
    }

    @Override
    public Mono<List<String>> listRegionNames() {
        return all().map(regions -> regions.stream().map(ConsoleRegion::name).toList());
    }

    /** Drops the cache, so the next read goes to the platform. */
    public void refresh() {
        cached.set(null);
    }

    private Mono<List<ConsoleRegion>> load() {
        return clients.internal().flatMap(platform -> platform.listRegions()
                .flatMapMany(Flux::fromIterable)
                .concatMap(region -> platform.getRegionAvailability(region.name())
                        .onErrorResume(e -> {
                            log.warn("availability of region {} unavailable: {}", region.name(), e.getMessage());
                            return Mono.just(new RegionAvailability(List.of(), List.of()));
                        })
                        .map(availability -> toConsole(region, availability)))
                .collectList())
                .doOnNext(regions -> log.info("regions loaded: {}", regions.stream().map(ConsoleRegion::name).toList()));
    }

    private static ConsoleRegion toConsole(Region region, RegionAvailability availability) {
        List<String> environments = region.environments() == null ? new ArrayList<>() : region.environments();
        return new ConsoleRegion(region.name(), region.title() == null ? region.name() : region.title(), region.status() == null ? "available" : region.status(),
                environments, availability.types() == null ? List.of() : availability.types(), availability.workflows() == null ? List.of() : availability.workflows());
    }
}
