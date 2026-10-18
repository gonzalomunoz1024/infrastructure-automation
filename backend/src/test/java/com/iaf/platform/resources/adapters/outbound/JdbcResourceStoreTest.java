package com.iaf.platform.resources.adapters.outbound;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

class JdbcResourceStoreTest {

    @Nested
    class OnH2 extends ResourceStoreContract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.h2();

        @Override
        ResourceStorePort newStore(PlatformProperties props) {
            JdbcResourceStore store = new JdbcResourceStore(db.jdbc(), props, CLOCK);
            store.seed();
            return store;
        }

        @Override
        ResourceStorePort reopen(PlatformProperties props) {
            return newStore(props);
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends ResourceStoreContract {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        ResourceStorePort newStore(PlatformProperties props) {
            JdbcResourceStore store = new JdbcResourceStore(db.jdbc(), props, CLOCK);
            store.seed();
            return store;
        }

        @Override
        ResourceStorePort reopen(PlatformProperties props) {
            return newStore(props);
        }
    }
}
