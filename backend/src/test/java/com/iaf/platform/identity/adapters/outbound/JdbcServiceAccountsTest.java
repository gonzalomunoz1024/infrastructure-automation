package com.iaf.platform.identity.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.Jdbc;
import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;

class JdbcServiceAccountsTest {

    abstract static class OnDatabase extends ServiceAccountStoreContract {

        abstract TestDatabase db();

        @Override
        ServiceAccountStore newStore() {
            return new JdbcServiceAccounts(db().jdbc());
        }

        @Override
        ServiceAccountStore reopen() {
            return new JdbcServiceAccounts(new Jdbc(db().dataSource()));
        }

        @Test
        void listsAccountsOldestFirst() {
            ServiceAccountStore store = newStore();
            ServiceAccount late = account("sa_late", "iaf_c", NOW.plusSeconds(1));
            ServiceAccount b = account("sa_b", "iaf_b", NOW);
            ServiceAccount a = account("sa_a", "iaf_a", NOW);
            store.save(late);
            store.save(b);
            store.save(a);
            assertThat(store.all()).containsExactly(a, b, late);
        }

        @Test
        void keepsNoTokenOnlyItsHash() {
            newStore().save(account("sa_1", "iaf_secret-one", NOW));
            List<String> row = db().jdbc().query("SELECT token_hash, doc FROM service_accounts", (rs, i) -> List.of(rs.getString(1), rs.getString(2))).get(0);
            assertThat(row).noneMatch(column -> column.contains("iaf_secret-one"));
            assertThat(row.get(0)).hasSize(64);
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
