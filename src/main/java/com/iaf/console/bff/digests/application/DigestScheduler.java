package com.iaf.console.bff.digests.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.support.CronExpression;

import com.iaf.console.bff.digests.domain.DigestWeeks;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Writes the digests on schedule (decision 32.4): every {@code check-every} each replica works out
 * the week the schedule's latest run is due to write (the week before that run's) and, until every
 * account's digest of it is written, writes what is missing; the claims let one replica write
 * each account. A run missed while every replica was down is so caught up at the next check.
 */
public class DigestScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    /** How long after start the first check waits, so the service is up before it reads its neighbours. */
    static final Duration FIRST_CHECK = Duration.ofSeconds(30);

    private final Digests digests;
    private final CronExpression cron;
    private final DigestsProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile LocalDate done;
    private volatile Disposable ticks;

    public DigestScheduler(Digests digests, DigestsProperties properties, Clock clock) {
        this.digests = digests;
        this.cron = CronExpression.parse(properties.cron());
        this.properties = properties;
        this.clock = clock;
    }

    /** The week the schedule's latest run at or before {@code now} writes; null when the schedule has not run in the eight days before. */
    public static LocalDate due(CronExpression cron, Instant now) {
        ZonedDateTime last = null;
        for (ZonedDateTime next = cron.next(now.atZone(ZoneOffset.UTC).minusDays(8)); next != null && !next.toInstant().isAfter(now);
                next = cron.next(next)) {
            last = next;
        }
        return last == null ? null : DigestWeeks.weekBefore(last.toInstant());
    }

    /** Writes the due week's missing digests, unless this replica saw them all written. */
    Mono<Void> check() {
        LocalDate week = due(cron, clock.instant());
        if (week == null || week.equals(done)) {
            return Mono.empty();
        }
        return digests.generate(week).doOnNext(outcomes -> {
            long written = outcomes.stream().filter(o -> Digests.WRITTEN.equals(o.result())).count();
            if (written > 0) {
                log.info("wrote {} weekly digests for the week of {}", written, week);
            }
            if (outcomes.stream().allMatch(Digests.Outcome::done)) {
                done = week;
            }
        }).then();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true) || !properties.scheduled()) {
            return;
        }
        ticks = Flux.interval(FIRST_CHECK, properties.checkEvery())
                .onBackpressureDrop()
                .concatMap(tick -> check().onErrorResume(e -> {
                    log.warn("the weekly digests could not be written now; the next check tries again: {}", e.toString());
                    return Mono.empty();
                }))
                .subscribe();
    }

    @Override
    public void stop() {
        running.set(false);
        Disposable t = ticks;
        if (t != null) {
            t.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
