package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.drift.drift.domain.DriftRules;
import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.RevertStatus;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Mono;

/**
 * Turns one read of a provider into the record: the observation goes to the state service (when
 * it changed, or once a day), the comparison opens, updates or closes the resource's one finding,
 * each change goes to the state service and onto {@code drift.findings}, and the watch gets its
 * next due time from its tier. A resource with a change in flight is left alone until it settles.
 */
public final class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final FindingStorePort findings;
    private final WatchStorePort watches;
    private final StateServicePort state;
    private final FindingEvents events;
    private final TierRules tiers;
    private final Timing timing;
    private final DriftMetrics metrics;
    private final Clock clock;
    private final ResourceListener listener;

    /** How often an unchanged observation is sent, and when a resource in flight or after a failed read is looked at again. */
    public record Timing(Duration observationRefresh, Duration inFlightRetry, Duration errorRetry) {
        public static final Timing DEFAULT = new Timing(Duration.ofHours(24), Duration.ofMinutes(1), Duration.ofMinutes(5));
    }

    public Reconciler(FindingStorePort findings, WatchStorePort watches, StateServicePort state, FindingEvents events, TierRules tiers,
            Timing timing, DriftMetrics metrics, Clock clock) {
        this(findings, watches, state, events, tiers, timing, metrics, clock, ResourceListener.NONE);
    }

    /** {@code listener} hears every successful read (the compliance scorecards). */
    public Reconciler(FindingStorePort findings, WatchStorePort watches, StateServicePort state, FindingEvents events, TierRules tiers,
            Timing timing, DriftMetrics metrics, Clock clock, ResourceListener listener) {
        this.listener = listener;
        this.findings = findings;
        this.watches = watches;
        this.state = state;
        this.events = events;
        this.tiers = tiers;
        this.timing = timing;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What one read did to the record. */
    public record Outcome(Watch watch, Finding finding, String action) {
        public static final String NONE = "none";
        public static final String OPENED = "opened";
        public static final String UPDATED = "updated";
        public static final String CLOSED = "closed";
        public static final String SKIPPED = "skipped";
        public static final String ERROR = "error";

        public ScanReport report() {
            return switch (action) {
                case OPENED -> new ScanReport(1, 1, 0, 0, 0, 0, 0);
                case UPDATED -> new ScanReport(1, 0, 1, 0, 0, 0, 0);
                case CLOSED -> new ScanReport(1, 0, 0, 1, 0, 0, 0);
                case ERROR -> ScanReport.errors(1);
                case SKIPPED -> ScanReport.deferred(1);
                default -> ScanReport.checked(1);
            };
        }
    }

    /**
     * Applies one read to the record. The watch is the copy the scan read; when another writer (the
     * sync, an accept, an ignore) changed it since, the read is applied again to the watch as it
     * stands, so a stale desired state never opens a finding nor undoes a decision.
     */
    public Mono<Outcome> apply(Watch watch, TypeInfo type, Observation observation) {
        return applyOnce(watch, type, observation)
                .onErrorResume(WatchStorePort.Conflict.class, e -> watches.find(watch.wfrn())
                        .flatMap(fresh -> applyOnce(fresh, type, observation))
                        .onErrorResume(WatchStorePort.Conflict.class, again -> {
                            log.info("{}: the watch keeps changing; the next scan reads it again", watch.wfrn());
                            return Mono.empty();
                        })
                        // gone (the resource left the inventory) or still changing: the record is left alone
                        .switchIfEmpty(Mono.fromSupplier(() -> new Outcome(watch, null, Outcome.SKIPPED))));
    }

    private Mono<Outcome> applyOnce(Watch watch, TypeInfo type, Observation observation) {
        Instant now = clock.instant();
        Duration typeInterval = type == null ? null : type.interval();
        if (watch.inFlight()) {
            Watch next = watch.toBuilder().lastCheckedAt(now).nextCheckAt(now.plus(timing.inFlightRetry())).build();
            return watches.save(next).map(w -> new Outcome(w, null, Outcome.SKIPPED));
        }
        if (observation.failed()) {
            metrics.error("observe");
            log.info("{}: the provider read failed: {}", watch.wfrn(), observation.error());
            Watch next = watch.toBuilder().lastCheckedAt(now).nextCheckAt(now.plus(timing.errorRetry())).build();
            return watches.save(next).map(w -> new Outcome(w, null, Outcome.ERROR));
        }
        Watch.Builder next = watch.toBuilder();
        DriftRules.Comparison comparison;
        if (observation.notFound()) {
            comparison = type != null && type.notFoundMeansDeleted() ? DriftRules.Comparison.inSync()
                    : DriftRules.missing(watch.desired(), watch.activeIgnores(now));
        } else {
            comparison = DriftRules.compare(watch.desired(), observation.observed(), type == null ? java.util.Set.of() : type.ignore(),
                    watch.activeIgnores(now), type == null ? java.util.Set.of() : type.mutable(), watch.production());
        }
        metrics.checked(watch.type(), 1);
        return sendObservation(watch, next, observation, now)
                .then(settle(watch, comparison, now))
                .flatMap(settled -> {
                    Finding finding = settled.finding();
                    if (finding != null) {
                        next.openFindingId(finding.open() ? finding.findingId() : null);
                        next.lastDriftAt(now);
                    } else if (watch.openFindingId() != null) {
                        next.openFindingId(null);
                    }
                    Watch staged = next.lastCheckedAt(now).build();
                    Tier tier = tiers.tierOf(staged, now);
                    Watch due = staged.toBuilder().tier(tier).nextCheckAt(tiers.nextCheck(staged, tier, typeInterval, now)).build();
                    return watches.save(due).flatMap(w -> heard(w, observation).thenReturn(new Outcome(w, finding, settled.action())));
                });
    }

    /** Tells the listener about the read; its failure is logged, never the read's. */
    private Mono<Void> heard(Watch watch, Observation observation) {
        return Mono.defer(() -> listener.observed(watch, observation.found() ? observation.observed() : null))
                .onErrorResume(e -> {
                    metrics.error("listener");
                    log.warn("{}: the read's listener failed: {}", watch.wfrn(), e.toString());
                    return Mono.empty();
                });
    }

    private record Settled(Finding finding, String action) {
        static final Settled NONE = new Settled(null, Outcome.NONE);
    }

    /** The observation goes to the state service when it changed, or when the last one is a day old. */
    private Mono<Void> sendObservation(Watch watch, Watch.Builder next, Observation observation, Instant now) {
        String digest = observation.digest();
        boolean fresh = watch.lastObservationAt() != null && watch.lastObservationAt().isAfter(now.minus(timing.observationRefresh()));
        if (digest.equals(watch.lastObservedDigest()) && fresh) {
            return Mono.empty();
        }
        return state.recordObservation(watch.wfrn(), observation, FindingEvents.ACTOR)
                .doOnSuccess(r -> {
                    metrics.stateCall("record-observation", "ok");
                    next.observation(now, digest);
                })
                .then()
                .onErrorResume(e -> {
                    metrics.stateCall("record-observation", "error");
                    log.warn("{}: the observation could not be recorded: {}", watch.wfrn(), e.toString());
                    return Mono.empty();
                });
    }

    /** Fails with a conflict when the watch changed since the scan read it: a change to the record is then decided again. */
    private Mono<Void> ensureFresh(Watch snapshot) {
        return watches.find(snapshot.wfrn())
                .switchIfEmpty(Mono.error(() -> new WatchStorePort.Conflict(snapshot.wfrn())))
                .flatMap(w -> w.rev() == snapshot.rev() ? Mono.<Void>empty() : Mono.<Void>error(new WatchStorePort.Conflict(snapshot.wfrn())));
    }

    /** Opens, updates, keeps or closes the resource's finding; none when there is none. */
    private Mono<Settled> settle(Watch watch, DriftRules.Comparison comparison, Instant now) {
        return findings.findOpenByWfrn(watch.wfrn()).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(open -> {
            if (comparison.drifted()) {
                if (open.isPresent()) {
                    Finding current = open.get();
                    if (current.sameFields(comparison.fields())) {
                        return findings.save(current.seen(now)).map(f -> new Settled(f, Outcome.NONE));
                    }
                    Finding changed = current.changed(comparison.fields(), comparison.severity(), now);
                    return ensureFresh(watch).then(findings.save(changed)).flatMap(saved -> !changed.storedAs(saved) ? Mono.just(new Settled(saved, Outcome.NONE))
                            : recordDrift(saved).then(events.updated(saved))
                                    .then(Mono.fromSupplier(() -> {
                                        metrics.finding("updated", saved.severity().id());
                                        return new Settled(saved, Outcome.UPDATED);
                                    })));
                }
                Finding opened = Finding.open("fnd-" + Ulid.next().toLowerCase(Locale.ROOT), watch.wfrn(), watch.accountId(), watch.region(),
                        watch.type(), comparison.severity(), comparison.fields(), now);
                return ensureFresh(watch).then(findings.save(opened)).flatMap(saved -> !opened.storedAs(saved) ? Mono.just(new Settled(saved, Outcome.NONE))
                        : recordDrift(saved).then(events.opened(saved))
                                .then(Mono.fromSupplier(() -> {
                                    metrics.finding("opened", saved.severity().id());
                                    log.info("{}: drift found, {} field(s), {}", watch.wfrn(), saved.drifted().size(), saved.severity().id());
                                    return new Settled(saved, Outcome.OPENED);
                                })));
            }
            if (open.isEmpty()) {
                return Mono.just(Settled.NONE);
            }
            Finding current = open.get();
            boolean afterRevert = current.revertJobId() != null && current.revertStatus() != RevertStatus.FAILED;
            Resolution how = afterRevert ? Resolution.REVERTED : Resolution.VANISHED;
            Finding closed = current.closed(how, afterRevert ? current.decidedBy() : FindingEvents.ACTOR, current.reason(), now, null);
            return ensureFresh(watch).then(findings.save(closed)).flatMap(saved -> !closed.storedAs(saved) ? Mono.just(new Settled(saved, Outcome.NONE))
                    : resolveDrift(saved).then(events.closed(saved))
                            .then(Mono.fromSupplier(() -> {
                                metrics.finding("closed", saved.severity().id());
                                log.info("{}: finding {} closed as {}", watch.wfrn(), saved.findingId(), how.id());
                                return new Settled(saved, Outcome.CLOSED);
                            })));
        });
    }

    /** Tells the state service; when it holds another finding open on the resource, that one has vanished. */
    Mono<Void> recordDrift(Finding f) {
        List<FindingField> fields = f.drifted().stream().map(x -> new FindingField(x.path(), x.expected(), x.observed(), null)).toList();
        return state.recordDrift(f.wfrn(), f.findingId(), f.severity(), fields, FindingEvents.ACTOR)
                .doOnSuccess(r -> metrics.stateCall("record-drift", "ok"))
                .then()
                .onErrorResume(StateServicePort.StateError.class, e -> {
                    String other = e.detailText("findingId");
                    if (e.status() == 409 && other != null && !other.equals(f.findingId())) {
                        return state.resolveDrift(f.wfrn(), other, Resolution.VANISHED, FindingEvents.ACTOR)
                                .then(state.recordDrift(f.wfrn(), f.findingId(), f.severity(), fields, FindingEvents.ACTOR))
                                .then();
                    }
                    return Mono.error(e);
                })
                .onErrorResume(e -> {
                    metrics.stateCall("record-drift", "error");
                    log.warn("{}: the finding could not be recorded with the state service: {}", f.wfrn(), e.toString());
                    return Mono.empty();
                });
    }

    Mono<Void> resolveDrift(Finding f) {
        return state.resolveDrift(f.wfrn(), f.findingId(), f.resolution(), f.decidedBy() != null ? f.decidedBy() : FindingEvents.ACTOR)
                .doOnSuccess(r -> metrics.stateCall("resolve-drift", "ok"))
                .then()
                .onErrorResume(e -> {
                    metrics.stateCall("resolve-drift", "error");
                    log.warn("{}: the resolution could not be recorded with the state service: {}", f.wfrn(), e.toString());
                    return Mono.empty();
                });
    }
}
