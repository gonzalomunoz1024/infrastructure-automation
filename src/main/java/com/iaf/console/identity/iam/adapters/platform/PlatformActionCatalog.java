package com.iaf.console.identity.iam.adapters.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.iam.domain.ActionCatalog;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.CatalogEntry;
import com.iaf.console.platform.model.ResourceType;

import reactor.core.publisher.Mono;

/**
 * The actions from the platform: every resource type's service gets the console's read verbs,
 * every catalog workflow adds its verb to its noun's service. Cached for a while; when the
 * platform cannot be reached, the last catalog (or the console's own services) is used, so a
 * policy write never waits on the platform.
 */
public final class PlatformActionCatalog implements ActionCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(PlatformActionCatalog.class);

    private final PlatformPort platform;
    private final Clock clock;
    private final Duration refresh;
    private volatile ActionCatalog cached = ActionCatalog.consoleOnly();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public PlatformActionCatalog(PlatformPort platform, Clock clock, Duration refresh) {
        this.platform = platform;
        this.clock = clock;
        this.refresh = refresh;
    }

    @Override
    public Mono<ActionCatalog> getCatalog() {
        if (clock.instant().isBefore(fetchedAt.plus(refresh))) {
            return Mono.just(cached);
        }
        return Mono.zip(platform.listResourceTypes(null).map(c -> c.body() == null ? List.<ResourceType>of() : c.body()),
                        platform.listCatalog(null).onErrorReturn(List.of()))
                .map(t -> {
                    ActionCatalog.Builder builder = ActionCatalog.builder();
                    for (ResourceType type : t.getT1()) {
                        builder.service(serviceOf(type));
                    }
                    for (CatalogEntry entry : t.getT2()) {
                        String service = t.getT1().stream().filter(type -> type.name().equals(entry.noun()) || type.plural() != null
                                && type.plural().equals(entry.noun())).map(PlatformActionCatalog::serviceOf).findFirst().orElse(entry.noun());
                        builder.verb(service, entry.verb());
                    }
                    return builder.build();
                })
                .doOnNext(catalog -> {
                    cached = catalog;
                    fetchedAt = clock.instant();
                })
                .onErrorResume(e -> {
                    log.warn("the platform's types and catalog could not be read, keeping the last action catalog: {}", e.toString());
                    fetchedAt = clock.instant().minus(refresh).plusSeconds(30);
                    return Mono.just(cached);
                });
    }

    /** The service of a type: its own {@code service} field, else its owner, else the type's name. */
    static String serviceOf(ResourceType type) {
        if (type.service() != null && !type.service().isBlank()) {
            return type.service();
        }
        if (type.owner() != null && !type.owner().isBlank()) {
            return type.owner().startsWith("team-") ? type.owner().substring(5) : type.owner();
        }
        return type.name();
    }
}
