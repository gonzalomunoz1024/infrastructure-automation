package com.iaf.platform.assistant.adapters.outbound;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;

class JdbcSessionStoreTest {

    abstract static class OnDatabase extends SessionStoreContract {

        abstract TestDatabase db();

        @Override
        SessionStore newStore() {
            return new JdbcSessionStore(db().jdbc());
        }

        @Override
        SessionStore reopen() {
            return new JdbcSessionStore(new Jdbc(db().dataSource()));
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
