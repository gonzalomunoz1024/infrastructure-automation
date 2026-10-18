package com.iaf.platform.notifications.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;
import com.mongodb.client.MongoCollection;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoInboxStoreTest extends InboxStoreContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    InboxStore newStore(int keep) {
        return new MongoInboxStore(db.mongo(), keep, Clock.fixed(T0, ZoneOffset.UTC));
    }

    MongoCollection<Document> notifications() {
        return db.database().getCollection(MongoInboxStore.COLLECTION);
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
        MongoInboxStore small = (MongoInboxStore) newStore(1);
        small.save(to("ntf_1", List.of("ana"), List.of("team-data")));
        small.markRead("ntf_1", "ana");
        small.save(to("ntf_2", List.of("ana"), List.of()));

        assertThat(small.size()).isEqualTo(1);
        Document kept = notifications().find().first();
        assertThat(kept.getString("_id")).isEqualTo("ntf_2");
        assertThat(kept.getList("audience", String.class)).containsExactly("user:ana");
        assertThat(kept.get("readers")).isNull();
    }

    @Test
    void theAudienceAndTheReadersLiveInTheNotificationsDocument() {
        store.save(to("ntf_1", List.of("ana"), List.of("team-data", "data-approvers")));
        store.markRead("ntf_1", "ana");
        store.markRead("ntf_1", "mateo");
        store.markRead("ntf_1", "ana");

        Document stored = notifications().find().first();
        assertThat(stored.getList("audience", String.class)).containsExactly("user:ana", "team:team-data", "team:data-approvers");
        List<Document> readers = stored.getList("readers", Document.class);
        assertThat(readers).extracting(r -> r.getString("user")).containsExactly("ana", "mateo");
        assertThat(readers.get(0).getDate("at")).isEqualTo(Date.from(T0));
    }

    @Test
    void replicasShareOneOrderOfArrival() throws Exception {
        InboxStore other = newStore(100);
        store.save(to("ntf_1", List.of("ana"), List.of()));
        other.save(to("ntf_2", List.of("ana"), List.of()));
        store.save(to("ntf_3", List.of("ana"), List.of()));
        assertThat(other.visibleTo(ANA)).extracting(Notification::id).containsExactly("ntf_3", "ntf_2", "ntf_1");

        InboxStore[] replicas = {store, other};
        race(16, i -> {
            replicas[i % 2].save(to("ntf_at_once_" + i, List.of("ana"), List.of()));
            return null;
        });

        assertThat(store.visibleTo(ANA)).hasSize(19);
        List<Long> places = notifications().find().map(d -> d.get("seq", Number.class).longValue()).into(new ArrayList<>());
        assertThat(places).doesNotHaveDuplicates().hasSize(19);
    }

    @Test
    void theFirstSaveOfAnIdWinsEvenWhenReplicasSaveItAtOnce() throws Exception {
        InboxStore[] replicas = {store, newStore(100)};
        race(16, i -> {
            replicas[i % 2].save(to("ntf_1", List.of("ana"), List.of()).withTitle("title " + i));
            return null;
        });

        assertThat(notifications().countDocuments()).isEqualTo(1);
        assertThat(store.visibleTo(ANA)).hasSize(1);
        assertThat(store.get("ntf_1")).hasValueSatisfying(n -> assertThat(n.title()).startsWith("title "));
    }

    @Test
    void aLostCounterStartsAgainAboveTheNewestNotification() {
        store.save(to("ntf_1", List.of("ana"), List.of()));
        store.save(to("ntf_2", List.of("ana"), List.of()));
        db.database().getCollection(MongoInboxStore.SEQUENCES).drop();

        InboxStore restarted = newStore(2);
        restarted.save(to("ntf_3", List.of("ana"), List.of()));

        assertThat(restarted.visibleTo(ANA)).extracting(Notification::id).containsExactly("ntf_3", "ntf_2");
    }

    interface Attempt<T> {
        T run(int i) throws Exception;
    }

    /** Runs {@code n} attempts at once, each on its own thread, and returns their results in order. */
    static <T> List<T> race(int n, Attempt<T> attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return attempt.run(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
