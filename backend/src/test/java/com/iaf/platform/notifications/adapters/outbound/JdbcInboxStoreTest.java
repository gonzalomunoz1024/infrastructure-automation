package com.iaf.platform.notifications.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.jdbc.TestDatabase;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;

class JdbcInboxStoreTest {

    abstract static class OnDatabase extends InboxStoreContract {

        abstract TestDatabase db();

        @Override
        InboxStore newStore(int keep) {
            return new JdbcInboxStore(db().jdbc(), keep, Clock.fixed(T0, ZoneOffset.UTC));
        }

        @Test
        void theInboxAndItsReadersOutliveTheInstance() {
            Notification n = to("ntf_1", List.of(), List.of("team-data"));
            store.save(n);
            store.markRead("ntf_1", "ana");

            InboxStore restarted = newStore(100);

            assertThat(restarted.visibleTo(ANA)).containsExactly(n);
            assertThat(restarted.readBy("ntf_1", "ana")).isTrue();
            assertThat(restarted.readBy("ntf_1", "mateo")).isFalse();
        }

        @Test
        void droppingTheOldestTakesItsAudienceAndReadersAlong() {
            JdbcInboxStore small = (JdbcInboxStore) newStore(1);
            small.save(to("ntf_1", List.of("ana"), List.of("team-data")));
            small.markRead("ntf_1", "ana");
            small.save(to("ntf_2", List.of("ana"), List.of()));

            assertThat(small.size()).isEqualTo(1);
            assertThat(db().jdbc().query("SELECT notification_id FROM inbox_audience", (rs, i) -> rs.getString(1))).containsOnly("ntf_2");
            assertThat(db().jdbc().query("SELECT notification_id FROM inbox_reads", (rs, i) -> rs.getString(1))).isEmpty();
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
