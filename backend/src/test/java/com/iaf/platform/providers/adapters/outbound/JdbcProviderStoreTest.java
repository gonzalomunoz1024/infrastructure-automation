package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;

class JdbcProviderStoreTest {

    abstract static class OnDatabase extends ProviderStoreContract {

        abstract TestDatabase db();

        JdbcProviderStore store(PlatformProperties props, Jdbc jdbc) {
            JdbcProviderStore store = new JdbcProviderStore(jdbc, props, Clock.fixed(NOW, ZoneOffset.UTC));
            store.init();
            return store;
        }

        @Override
        ProviderStorePort newStore(PlatformProperties props) {
            return store(props, db().jdbc());
        }

        @Override
        ProviderStorePort reopen(PlatformProperties props) {
            return store(props, new Jdbc(db().dataSource()));
        }

        @Test
        void seedingAgainAddsNothing() throws Exception {
            write(ECHO);
            newStore(props(content, false));
            assertThat(((JdbcProviderStore) reopen(props(content, false))).seed()).isZero();
        }

        @Test
        void aProviderStillAsSeededTakesItsChangedFile() throws Exception {
            write(ECHO);
            newStore(props(content, false));
            write(ECHO.replace("https://echo.example", "https://moved.example"));
            StoredProvider refreshed = reopen(props(content, false)).get("echo").orElseThrow();
            assertThat(refreshed.yaml()).contains("https://moved.example");
            assertThat(refreshed.updatedBy()).isEqualTo("content");
        }

        @Test
        void anotherInstanceSeesAWriteAtOnce() throws Exception {
            write(ECHO);
            ProviderStorePort store = newStore(props(content, false));
            ProviderStorePort other = reopen(props(content, false));
            assertThat(other.get("echo")).isPresent();
            store.delete("echo");
            assertThat(other.get("echo")).isEmpty();
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
