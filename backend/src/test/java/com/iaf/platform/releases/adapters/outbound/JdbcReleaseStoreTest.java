package com.iaf.platform.releases.adapters.outbound;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

class JdbcReleaseStoreTest {

    @Nested
    class OnH2 extends ReleaseStoreContract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        ReleaseStore newStore() {
            return new JdbcReleaseStore(db.jdbc());
        }

        @Override
        ReleaseStore reopen() {
            return new JdbcReleaseStore(db.jdbc());
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends ReleaseStoreContract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        ReleaseStore newStore() {
            return new JdbcReleaseStore(db.jdbc());
        }

        @Override
        ReleaseStore reopen() {
            return new JdbcReleaseStore(db.jdbc());
        }
    }
}
