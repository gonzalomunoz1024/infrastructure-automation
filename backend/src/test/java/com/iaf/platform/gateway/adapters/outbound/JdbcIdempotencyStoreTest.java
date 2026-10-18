package com.iaf.platform.gateway.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

class JdbcIdempotencyStoreTest {

    abstract static class OnDatabase extends IdempotencyStoreContract {

        abstract TestDatabase db();

        @Override
        IdempotencyStore newStore() {
            return new JdbcIdempotencyStore(db().jdbc(), Clock.fixed(T0, ZoneOffset.UTC));
        }

        @Test
        void anotherReplicaSeesTheClaimAndThenTheAnswer() {
            IdempotencyStore other = newStore();
            store.claim(request("ana", "k1", "fp1", T0), T0);
            assertThat(other.claim(request("ana", "k1", "fp2", T0), T0)).hasValueSatisfying(e -> {
                assertThat(e.fingerprint()).isEqualTo("fp1");
                assertThat(e.answered()).isFalse();
            });
            store.complete("ana", "k1", OK);
            assertThat(other.claim(request("ana", "k1", "fp1", T0), T0)).hasValueSatisfying(e -> assertThat(e.answer()).isEqualTo(OK));
            other.release("ana", "k1");
            assertThat(store.claim(request("ana", "k1", "fp1", T0), T0)).as("an answered key is never released").isPresent();
        }

        @Test
        void theSweepDeletesTheKeysPastTheirExpiryOnly() {
            JdbcIdempotencyStore jdbc = (JdbcIdempotencyStore) store;
            Instant hourLater = T0.plusSeconds(3600);
            jdbc.claim(request("ana", "k1", "fp1", T0), T0);
            jdbc.complete("ana", "k1", OK);
            jdbc.claim(request("ana", "k2", "fp2", hourLater), hourLater);
            assertThat(jdbc.size()).isEqualTo(2);

            assertThat(jdbc.sweep(T0.plus(DAY).plusSeconds(1))).isEqualTo(1);

            assertThat(jdbc.size()).isEqualTo(1);
            assertThat(jdbc.claim(request("ana", "k2", "fp3", hourLater), hourLater)).hasValueSatisfying(e -> assertThat(e.fingerprint()).isEqualTo("fp2"));
            assertThat(jdbc.sweep(T0.plus(DAY).plusSeconds(1))).isZero();
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
