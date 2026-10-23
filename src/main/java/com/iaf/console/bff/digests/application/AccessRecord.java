package com.iaf.console.bff.digests.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.digests.domain.AccessChanges;
import com.iaf.console.bff.digests.domain.DigestAccount;
import com.iaf.console.bff.digests.domain.SubjectState;
import com.iaf.console.bff.digests.ports.outbound.AccessLedger;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The weekly digest's record of the identity events the BFF follows (decision 32.4): every group,
 * user, policy and access key event goes into the ledger with what it granted, one event after
 * another, so each change is told against the state the one before it left.
 */
public class AccessRecord implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccessRecord.class);

    static final Set<String> AGGREGATES = Set.of("group", "user", "policy", "accessKey");
    /** The subjects whose state the ledger keeps; an access key's own event says all there is. */
    static final Set<String> STATEFUL = Set.of("group", "user", "policy");

    private final AccessLedger ledger;
    private final boolean followsIdentityEvents;
    private final Clock clock;
    private final Sinks.Many<EventEnvelope> queue = Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Disposable subscription;

    public AccessRecord(AccessLedger ledger, boolean followsIdentityEvents, Clock clock) {
        this.ledger = ledger;
        this.followsIdentityEvents = followsIdentityEvents;
        this.clock = clock;
    }

    /** Queues an identity event for the ledger; any other event is not the digest's. */
    public void onConsoleEvent(EventEnvelope event) {
        if (event == null || event.aggregate() == null || !AGGREGATES.contains(event.aggregate())) {
            return;
        }
        try {
            queue.emitNext(event, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
        } catch (Sinks.EmissionException e) {
            log.warn("the identity event {} was not recorded for the weekly digest: {}", event.key(), e.toString());
        }
    }

    /** Records one identity event: what it granted and the subject's state after it. */
    public Mono<Void> record(EventEnvelope event) {
        return Mono.defer(() -> {
            String accountId = event.accountId();
            JsonNode data = event.data();
            if (!AccountScope.isAccountId(accountId) || event.aggregateId() == null || data == null) {
                return Mono.<Void>empty();
            }
            String aggregate = event.aggregate();
            String action = data.path("action").asText(null);
            long version = data.path("version").asLong(0);
            Instant at = event.at() == null ? clock.instant() : event.at();
            AccountScope scope = AccountScope.of(accountId, DigestAccount.NO_REGION, DigestSources.CONSOLE);
            boolean stateful = STATEFUL.contains(aggregate);
            Mono<Optional<SubjectState>> before = stateful
                    ? ledger.findState(scope, aggregate, event.aggregateId()).map(Optional::of).defaultIfEmpty(Optional.empty())
                    : Mono.just(Optional.empty());
            return before
                    .flatMap(state -> {
                        AccessChanges.Change change = AccessChanges.of(aggregate, event.aggregateId(), action, version, state.orElse(null),
                                data.get("snapshot"), event.actor(), at);
                        return ledger.record(accountId, event.key(), at, change.facts(), stateful ? aggregate : null, event.aggregateId(),
                                "deleted".equals(action) ? null : change.after());
                    });
        });
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        subscription = queue.asFlux().concatMap(event -> record(event).onErrorResume(e -> {
            log.warn("the identity event {} was not recorded for the weekly digest: {}", event.key(), e.toString());
            return Mono.empty();
        })).subscribe();
        if (followsIdentityEvents) {
            ledger.follow(clock.instant()).subscribe(since -> log.info("the weekly digest counts identity events since {}", since),
                    e -> log.warn("the start of the identity events' record could not be noted: {}", e.toString()));
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Disposable s = subscription;
        if (s != null) {
            s.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
