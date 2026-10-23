package com.iaf.console.search.index.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.Checkpoint;
import com.iaf.console.search.index.domain.Documents;
import com.iaf.console.search.index.domain.EventOutcome;
import com.iaf.console.search.index.domain.FindingEvents;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.ResourceEvents;
import com.iaf.console.search.index.domain.ResourceTypeHints;
import com.iaf.console.search.index.domain.StateResource;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * Folds one event onto the index: the document held, the event, the type's hints, then the
 * write; when the fold asks for it, the whole resource is read from the state service instead.
 * The checkpoint remembers the newest event applied, for the status's lag.
 */
public final class IndexingService {

    /** The principal this service acts as when it reads for itself. */
    public static final String SELF = "console-search-indexer";
    /** The checkpoint of the events applied, whatever their source. */
    public static final String EVENTS = "events";

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final SearchIndexPort index;
    private final StateServicePort state;
    private final ResourceTypesPort types;
    private final CheckpointPort checkpoints;
    private final IndexMetrics metrics;
    private final Clock clock;

    public IndexingService(SearchIndexPort index, StateServicePort state, ResourceTypesPort types, CheckpointPort checkpoints, IndexMetrics metrics,
            Clock clock) {
        this.index = index;
        this.state = state;
        this.types = types;
        this.checkpoints = checkpoints;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Applies one event of {@code state.resources} or {@code drift.findings}; other events are ignored. */
    public Mono<EventOutcome> apply(EventEnvelope event) {
        boolean resource = ResourceEvents.isResourceEvent(event);
        boolean finding = FindingEvents.isFindingEvent(event);
        if (!resource && !finding) {
            metrics.event(event.type(), "ignored");
            return Mono.just(new EventOutcome.Ignored("not a resource or finding event"));
        }
        String wfrn = event.context().wfrn();
        AccountScope scope;
        try {
            scope = scopeOf(event.context().accountId(), event.context().region(), wfrn);
        } catch (IllegalArgumentException e) {
            metrics.event(event.type(), "ignored");
            return Mono.just(new EventOutcome.Ignored("bad wfrn or account: " + e.getMessage()));
        }
        Mono<ResourceTypeHints> hints = resource ? hintsOf(event, wfrn) : Mono.just(ResourceTypeHints.unknown(Wfrn.parse(wfrn).type()));
        return Mono.zip(index.find(scope, wfrn).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()), hints)
                .flatMap(t -> {
                    IndexedResource current = t.getT1().orElse(null);
                    EventOutcome outcome = resource ? ResourceEvents.apply(current, event, t.getT2()) : FindingEvents.apply(current, event);
                    return settle(scope, outcome, event.type(), event.at());
                });
    }

    /** Reads a resource from the state service and writes what it is now. */
    public Mono<EventOutcome> refetch(AccountScope scope, String wfrn) {
        metrics.refetched();
        return Mono.zip(index.find(scope, wfrn).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
                state.find(scope, wfrn).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()))
                .flatMap(t -> {
                    IndexedResource previous = t.getT1().orElse(null);
                    StateResource found = t.getT2().orElse(null);
                    if (found == null) {
                        log.info("{} is unknown to the state service: removed from the index", wfrn);
                        return index.remove(scope.accountId(), wfrn).thenReturn(new EventOutcome.Ignored("unknown to the state service"));
                    }
                    String type = found.type() != null ? found.type() : Wfrn.parse(wfrn).type();
                    return types.find(type).defaultIfEmpty(ResourceTypeHints.unknown(type))
                            .flatMap(h -> {
                                IndexedResource document = Documents.fromState(found, h, previous);
                                if (document == null) {
                                    return Mono.just(new EventOutcome.Ignored("the state service's answer is not a resource"));
                                }
                                return index.index(document).thenReturn((EventOutcome) new EventOutcome.Applied(document));
                            });
                });
    }

    /** The document of a resource the state service serves, as a rebuild writes it. */
    public Mono<IndexedResource> documentOf(StateResource resource, AccountScope scope) {
        String wfrn = resource.wfrn();
        if (wfrn == null || !Wfrn.isValid(wfrn)) {
            return Mono.empty();
        }
        String type = resource.type() != null ? resource.type() : Wfrn.parse(wfrn).type();
        Mono<IndexedResource> previous = resource.tombstone() ? index.find(scope, wfrn) : Mono.empty();
        return Mono.zip(types.find(type).defaultIfEmpty(ResourceTypeHints.unknown(type)),
                previous.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()))
                .flatMap(t -> Mono.justOrEmpty(Documents.fromState(resource, t.getT1(), t.getT2().orElse(null))));
    }

    private Mono<EventOutcome> settle(AccountScope scope, EventOutcome outcome, String type, Instant at) {
        return switch (outcome) {
            case EventOutcome.Applied applied -> index.index(applied.document()).then(checkpoint(at))
                    .doOnSuccess(v -> metrics.event(type, "applied")).thenReturn(outcome);
            case EventOutcome.Refetch refetch -> {
                log.debug("{} on {}: {}; reading the resource again", type, refetch.wfrn(), refetch.reason());
                yield refetch(scope, refetch.wfrn()).flatMap(o -> checkpoint(at).thenReturn(o)).doOnSuccess(v -> metrics.event(type, "refetched"));
            }
            case EventOutcome.Ignored ignored -> checkpoint(at).doOnSuccess(v -> metrics.event(type, "ignored")).thenReturn(outcome);
        };
    }

    private Mono<Void> checkpoint(Instant eventAt) {
        Instant now = clock.instant();
        return checkpoints.find(EVENTS).defaultIfEmpty(Checkpoint.empty(EVENTS))
                .flatMap(c -> checkpoints.save(c.advanced(eventAt, now)))
                .doOnNext(c -> {
                    if (c.eventAt() != null) {
                        metrics.lag(Math.max(0, java.time.Duration.between(c.eventAt(), now).toMillis() / 1000.0));
                    }
                })
                .then();
    }

    private Mono<ResourceTypeHints> hintsOf(EventEnvelope event, String wfrn) {
        String type = com.iaf.console.search.index.domain.Json.text(event.data(), "resourceType");
        if (type == null) {
            type = Wfrn.parse(wfrn).type();
        }
        return types.find(type).defaultIfEmpty(ResourceTypeHints.unknown(type));
    }

    /** The scope an event is applied in: its account and region, this service as the principal. */
    public static AccountScope scopeOf(String accountId, String region, String wfrn) {
        Wfrn name = Wfrn.parse(wfrn);
        String account = accountId != null ? accountId : name.account();
        String r = region != null ? region : name.region();
        if (!account.equals(name.account())) {
            throw new IllegalArgumentException("the event's account " + account + " is not the wfrn's " + name.account());
        }
        return AccountScope.of(account, r.isBlank() ? "global" : r, SELF);
    }
}
