package com.iaf.console.identity.principals.adapters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.support.CronExpression;

import com.iaf.console.identity.principals.application.GroupSyncReport;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs the identity provider group sync when the cron expression fires in its zone (02:00 UTC
 * every day by default, {@code console.identity.lifecycle.group-sync}), while the service is up.
 * A failed run is logged and the next one comes as planned. Off when {@code enabled} is false.
 */
public final class GroupSyncSchedule implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GroupSyncSchedule.class);

    private final Supplier<Mono<GroupSyncReport>> run;
    private final CronExpression cron;
    private final ZoneId zone;
    private final Clock clock;
    private final boolean enabled;
    private Disposable schedule;

    public GroupSyncSchedule(Supplier<Mono<GroupSyncReport>> run, String cron, ZoneId zone, Clock clock, boolean enabled) {
        this.run = run;
        this.cron = CronExpression.parse(cron);
        this.zone = zone;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** How long from {@code now} until the expression next fires in {@code zone}; null when it never does. */
    static Duration untilNext(CronExpression cron, ZoneId zone, Instant now) {
        ZonedDateTime next = cron.next(now.atZone(zone));
        return next == null ? null : Duration.between(now, next.toInstant());
    }

    @Override
    public boolean isAutoStartup() {
        return enabled;
    }

    @Override
    public synchronized void start() {
        if (schedule != null) {
            return;
        }
        schedule = Mono.defer(() -> {
                    Duration wait = untilNext(cron, zone, clock.instant());
                    return wait == null ? Mono.<Long>never() : Mono.delay(wait, Schedulers.boundedElastic());
                })
                .then(Mono.defer(() -> run.get()
                        .doOnNext(report -> log.info("group sync: {} accounts, {} groups added, {} removed, {} removals skipped, {} accounts failed",
                                report.accounts().size(), report.added(), report.removed(), report.skipped(), report.failed()))
                        .onErrorResume(e -> {
                            log.warn("the group sync failed: {}", e.toString());
                            return Mono.empty();
                        })))
                .repeat()
                .subscribe();
    }

    @Override
    public synchronized void stop() {
        if (schedule != null) {
            schedule.dispose();
            schedule = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return schedule != null;
    }
}
