package com.iaf.console.identity.principals.adapters;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import com.iaf.console.identity.common.LifecycleProperties;
import com.iaf.console.identity.principals.application.GroupSyncReport;

import reactor.core.publisher.Mono;

/** When the nightly group sync runs, and that a failed run does not end the schedule. */
class GroupSyncScheduleTest {

    private static final CronExpression NIGHTLY = CronExpression.parse(LifecycleProperties.GroupSync.DEFAULT_CRON);

    @Test
    void runsAtTwoInTheMorningUtcByDefault() {
        LifecycleProperties.GroupSync defaults = new LifecycleProperties(null).groupSync();
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.cron()).isEqualTo("0 0 2 * * *");
        assertThat(defaults.zone()).isEqualTo(ZoneOffset.UTC);

        assertThat(GroupSyncSchedule.untilNext(NIGHTLY, ZoneOffset.UTC, Instant.parse("2026-10-23T01:59:00Z"))).isEqualTo(Duration.ofMinutes(1));
        assertThat(GroupSyncSchedule.untilNext(NIGHTLY, ZoneOffset.UTC, Instant.parse("2026-10-23T02:00:00Z"))).isEqualTo(Duration.ofDays(1));
        assertThat(GroupSyncSchedule.untilNext(NIGHTLY, ZoneOffset.UTC, Instant.parse("2026-10-23T02:00:00.300Z")))
                .isEqualTo(Duration.ofDays(1).minusMillis(300));
        assertThat(GroupSyncSchedule.untilNext(NIGHTLY, ZoneOffset.UTC, Instant.parse("2026-10-23T23:00:00Z"))).isEqualTo(Duration.ofHours(3));
        assertThat(GroupSyncSchedule.untilNext(NIGHTLY, ZoneId.of("Europe/Madrid"), Instant.parse("2026-10-23T00:30:00Z")))
                .as("02:00 in Madrid is 00:00 UTC in October").isEqualTo(Duration.ofMinutes(23 * 60 + 30));
    }

    @Test
    void offWhenDisabled() {
        GroupSyncSchedule off = new GroupSyncSchedule(() -> Mono.error(new AssertionError("never runs")), "* * * * * *", ZoneOffset.UTC, Clock.systemUTC(),
                false);
        assertThat(off.isAutoStartup()).isFalse();
        assertThat(off.isRunning()).isFalse();
    }

    @Test
    void keepsRunningAfterAFailedRunUntilStopped() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch twice = new CountDownLatch(2);
        GroupSyncSchedule schedule = new GroupSyncSchedule(() -> Mono.defer(() -> {
            twice.countDown();
            return runs.incrementAndGet() == 1 ? Mono.error(new IllegalStateException("the directory is down"))
                    : Mono.just(new GroupSyncReport(Instant.now(), Instant.now(), List.of()));
        }), "* * * * * *", ZoneOffset.UTC, Clock.systemUTC(), true);
        assertThat(schedule.isAutoStartup()).isTrue();
        schedule.start();
        try {
            assertThat(schedule.isRunning()).isTrue();
            assertThat(twice.await(10, TimeUnit.SECONDS)).as("a second run after the failed one").isTrue();
        } finally {
            schedule.stop();
        }
        assertThat(schedule.isRunning()).isFalse();
        int stoppedAt = runs.get();
        Thread.sleep(1500);
        assertThat(runs.get()).as("no run after stop").isEqualTo(stoppedAt);
    }
}
