package com.iaf.platform.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.TooManyRequestsException;

import reactor.test.StepVerifier;

class JobQuotasTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    /** A clock the test moves. */
    static final class MovingClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(T0);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static Identity person(String id, String... teams) {
        return new Identity(id, id, null, List.of(teams), List.of("ops"), false, Identity.Kind.USER);
    }

    static GatewayProperties.Quotas quotas(int perMinute, int burst) {
        GatewayProperties.Quotas q = new GatewayProperties.Quotas();
        q.setPerMinute(perMinute);
        q.setBurst(burst);
        return q;
    }

    final MovingClock clock = new MovingClock();

    @Test
    void aTeamOverItsQuotaGetsA429WithWhenToRetry() {
        JobQuotas quotas = new JobQuotas(quotas(6, 2), clock);
        Identity mateo = person("mateo", "team-compute");
        Identity ana = person("ana", "team-compute");

        StepVerifier.create(quotas.take(mateo)).verifyComplete();
        StepVerifier.create(quotas.take(ana)).verifyComplete();
        StepVerifier.create(quotas.take(mateo))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(TooManyRequestsException.class)
                            .hasMessage("Team team-compute has used its quota of 6 job starts a minute (2 at once); try again in 10 s");
                    assertThat(((TooManyRequestsException) e).retryAfterSeconds()).isEqualTo(10);
                })
                .verify();

        clock.now.set(T0.plusSeconds(10));
        StepVerifier.create(quotas.take(ana)).verifyComplete();
    }

    @Test
    void everyTeamHasItsOwnBucketAndOverridesWin() {
        GatewayProperties.Quotas config = quotas(60, 1);
        GatewayProperties.Limit data = new GatewayProperties.Limit();
        data.setBurst(3);
        config.getTeams().put("team-data", data);
        JobQuotas quotas = new JobQuotas(config, clock);

        StepVerifier.create(quotas.take(person("mateo", "team-compute"))).verifyComplete();
        StepVerifier.create(quotas.take(person("mateo", "team-compute"))).expectError(TooManyRequestsException.class).verify();
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(quotas.take(person("sofia", "team-data"))).verifyComplete();
        }
        StepVerifier.create(quotas.take(person("sofia", "team-data"))).expectError(TooManyRequestsException.class).verify();
    }

    @Test
    void aCallerIsChargedToTheirFirstTeamOrToThemselves() {
        assertThat(JobQuotas.bucketOf(person("gonzalo", "team-compute", "team-platform"))).isEqualTo("team-compute");
        assertThat(JobQuotas.bucketOf(person("guest"))).isEqualTo("user:guest");

        JobQuotas quotas = new JobQuotas(quotas(60, 1), clock);
        StepVerifier.create(quotas.take(person("guest"))).verifyComplete();
        StepVerifier.create(quotas.take(person("visitor"))).verifyComplete();
        StepVerifier.create(quotas.take(person("guest")))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageStartingWith("guest (in no team) has used its quota"))
                .verify();
    }

    @Test
    void thePlatformItselfAndDisabledQuotasAreNeverLimited() {
        JobQuotas quotas = new JobQuotas(quotas(1, 1), clock);
        for (int i = 0; i < 5; i++) {
            StepVerifier.create(quotas.take(Identity.SYSTEM)).verifyComplete();
        }
        GatewayProperties.Quotas off = quotas(1, 1);
        off.setEnabled(false);
        JobQuotas none = new JobQuotas(off, clock);
        for (int i = 0; i < 5; i++) {
            StepVerifier.create(none.take(person("mateo", "team-compute"))).verifyComplete();
            StepVerifier.create(JobQuotas.unlimited().take(person("mateo", "team-compute"))).verifyComplete();
        }
    }
}
