package com.iaf.platform.gateway.application;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.gateway.adapters.outbound.JdbcIdempotencyStore;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

/** The handler over the JDBC store: the same contract as in memory. */
class JdbcIdempotencyHandlerTest {

    abstract static class OnDatabase extends IdempotencyHandlerContract {

        abstract TestDatabase db();

        @Override
        IdempotencyStore newStore() {
            return new JdbcIdempotencyStore(db().jdbc(), CLOCK);
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
