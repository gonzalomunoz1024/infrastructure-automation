package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.providers.ports.outbound.ProviderAlertStatePort;

class JdbcProviderAlertStateTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");

    abstract static class OnDatabase {

        abstract TestDatabase db();

        @Test
        void nothingIsKeptBeforeTheFirstEvaluation() {
            assertThat(new JdbcProviderAlertState(db().jdbc()).lastEvaluatedAt()).isEmpty();
        }

        @Test
        void theExactInstantSurvivesARestart() {
            ProviderAlertStatePort state = new JdbcProviderAlertState(db().jdbc());
            state.lastEvaluatedAt(T0);
            assertThat(state.lastEvaluatedAt()).contains(T0);
            ProviderAlertStatePort restarted = new JdbcProviderAlertState(new Jdbc(db().dataSource()));
            assertThat(restarted.lastEvaluatedAt()).contains(T0);
        }

        @Test
        void itOnlyMovesForwardAndStaysOneRow() {
            ProviderAlertStatePort state = new JdbcProviderAlertState(db().jdbc());
            state.lastEvaluatedAt(T0);
            state.lastEvaluatedAt(T0.minusSeconds(60));
            assertThat(state.lastEvaluatedAt()).contains(T0);
            state.lastEvaluatedAt(T0.plusSeconds(60));
            state.lastEvaluatedAt(null);
            assertThat(state.lastEvaluatedAt()).contains(T0.plusSeconds(60));
            assertThat(db().jdbc().first("SELECT COUNT(*) FROM provider_alert_state", (rs, i) -> rs.getLong(1))).contains(1L);
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

    @Test
    void theDemoKeepsItInMemoryAndOnlyForward() {
        InMemoryProviderAlertState state = new InMemoryProviderAlertState();
        assertThat(state.lastEvaluatedAt()).isEmpty();
        state.lastEvaluatedAt(T0);
        state.lastEvaluatedAt(T0.minusSeconds(1));
        state.lastEvaluatedAt(null);
        assertThat(state.lastEvaluatedAt()).contains(T0);
    }
}
