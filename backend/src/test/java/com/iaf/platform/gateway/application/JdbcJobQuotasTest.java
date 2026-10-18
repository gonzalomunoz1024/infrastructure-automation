package com.iaf.platform.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.gateway.adapters.outbound.JdbcQuotaStore;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;

import reactor.test.StepVerifier;

/** The quotas over the JDBC store: the same contract as in memory, and the buckets every replica shares. */
class JdbcJobQuotasTest {

    abstract static class OnDatabase extends JobQuotasContract {

        abstract TestDatabase db();

        @Override
        QuotaStore newStore() {
            return new JdbcQuotaStore(db().jdbc(), clock);
        }

        @Test
        void everyReplicaDrawsFromTheSameBucket() {
            JobQuotas replicaA = quotas(quotas(60, 2));
            JobQuotas replicaB = new JobQuotas(quotas(60, 2), newStore(), clock);

            StepVerifier.create(replicaA.take(person("mateo", "team-compute"))).verifyComplete();
            StepVerifier.create(replicaB.take(person("ana", "team-compute"))).verifyComplete();
            StepVerifier.create(replicaA.take(person("mateo", "team-compute"))).expectError(TooManyRequestsException.class).verify();
            StepVerifier.create(replicaB.take(person("ana", "team-compute"))).expectError(TooManyRequestsException.class).verify();
        }

        @Test
        void aBucketKeepsItsTokensAndRefillsFromWhenItWasLastDrawn() {
            JdbcQuotaStore jdbc = (JdbcQuotaStore) store;
            assertThat(jdbc.take("team-data", 6, 3, T0)).isEmpty();
            assertThat(jdbc.take("team-data", 6, 3, T0)).isEmpty();
            assertThat(jdbc.tokensOf("team-data")).contains(1d);

            Instant later = T0.plusSeconds(20);   // a token every 10 s
            assertThat(jdbc.take("team-data", 6, 3, later)).isEmpty();
            assertThat(jdbc.take("team-data", 6, 3, later)).isEmpty();
            assertThat(jdbc.take("team-data", 6, 3, later)).isEmpty();
            assertThat(jdbc.take("team-data", 6, 3, later)).contains(Duration.ofSeconds(10));
        }

        @Test
        void aLoweredLimitNeverLeavesMoreTokensThanItAllows() {
            JdbcQuotaStore jdbc = (JdbcQuotaStore) store;
            assertThat(jdbc.take("team-data", 60, 5, T0)).isEmpty();
            assertThat(jdbc.tokensOf("team-data")).contains(4d);

            assertThat(jdbc.take("team-data", 60, 2, T0)).isEmpty();
            assertThat(jdbc.tokensOf("team-data")).contains(1d);
            assertThat(jdbc.take("team-data", 60, 2, T0)).isEmpty();
            assertThat(jdbc.take("team-data", 60, 2, T0)).isPresent();
        }

        @Test
        void theSweepForgetsBucketsIdleLongEnoughToBeFull() {
            JdbcQuotaStore jdbc = (JdbcQuotaStore) store;
            jdbc.take("team-data", 60, 5, T0);
            jdbc.take("team-compute", 60, 5, T0.plus(Duration.ofHours(2)));

            assertThat(jdbc.sweep(T0.plus(Duration.ofHours(25)))).isEqualTo(1);

            assertThat(jdbc.tokensOf("team-data")).isEmpty();
            assertThat(jdbc.tokensOf("team-compute")).contains(4d);
            assertThat(jdbc.take("team-data", 60, 5, T0.plus(Duration.ofHours(25)))).as("a swept bucket starts full again").isEmpty();
        }
    }

    @Nested
    class OnH2 extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        TestDatabase db() {
            return db;
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        TestDatabase db() {
            return db;
        }
    }
}
