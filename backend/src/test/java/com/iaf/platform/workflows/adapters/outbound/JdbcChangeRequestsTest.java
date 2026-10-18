package com.iaf.platform.workflows.adapters.outbound;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

class JdbcChangeRequestsTest {

    @Nested
    class OnH2 extends ChangeRequestsContract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        ChangeRequestPort newStore() {
            return new JdbcChangeRequests(db.jdbc());
        }

        @Override
        ChangeRequestPort reopen() {
            return new JdbcChangeRequests(db.jdbc());
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends ChangeRequestsContract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        ChangeRequestPort newStore() {
            return new JdbcChangeRequests(db.jdbc());
        }

        @Override
        ChangeRequestPort reopen() {
            return new JdbcChangeRequests(db.jdbc());
        }
    }
}
