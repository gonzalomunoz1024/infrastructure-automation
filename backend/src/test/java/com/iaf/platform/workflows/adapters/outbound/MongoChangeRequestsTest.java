package com.iaf.platform.workflows.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Status;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoChangeRequestsTest extends ChangeRequestsContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    ChangeRequestPort newStore() {
        return new MongoChangeRequests(db.mongo());
    }

    @Override
    ChangeRequestPort reopen() {
        return new MongoChangeRequests(db.mongo());
    }

    /**
     * Two instances on one database, each past the handler's check that the change request is
     * open, write their merge (or one a merge and the other a close) at the same moment: in every
     * round exactly one lands, the other is a 409, and the stored change request is the winner's.
     */
    @Test
    void twoInstancesDecidingOnTheSameChangeRequestAtOnceExactlyOneWins() throws Exception {
        List<ChangeRequestPort> instances = List.of(new MongoChangeRequests(db.mongo()), new MongoChangeRequests(new Mongo(db.database())));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 40; round++) {
                String id = "cr_race" + round;
                store.save(cr(id, T0));
                Status second = round % 2 == 0 ? Status.MERGED : Status.CLOSED;
                CyclicBarrier bothChecked = new CyclicBarrier(2);
                List<Future<Boolean>> outcomes = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    ChangeRequestPort instance = instances.get(i);
                    Status decision = i == 0 ? Status.MERGED : second;
                    String by = "reviewer-" + i;
                    outcomes.add(pool.submit(() -> {
                        ChangeRequest open = instance.get(id).orElseThrow();
                        assertThat(open.status()).isEqualTo(Status.OPEN);
                        bothChecked.await(10, TimeUnit.SECONDS);
                        try {
                            instance.save(open.withStatus(decision, T0.plusSeconds(90), by));
                            return true;
                        } catch (ConflictException e) {
                            assertThat(e.getMessage()).isIn("change request " + id + " is merged", "change request " + id + " is closed");
                            return false;
                        }
                    }));
                }
                boolean first = outcomes.get(0).get(20, TimeUnit.SECONDS);
                boolean other = outcomes.get(1).get(20, TimeUnit.SECONDS);
                assertThat(first ^ other).as("round %d: exactly one decision lands", round).isTrue();
                ChangeRequest stored = store.get(id).orElseThrow();
                assertThat(stored.status()).isEqualTo(first ? Status.MERGED : second);
                if (stored.status() == Status.MERGED) {
                    assertThat(stored.mergedBy()).isEqualTo(first ? "reviewer-0" : "reviewer-1");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDecidedChangeRequestTakesNoSecondDecision() {
        ChangeRequest open = cr("cr_1", T0);
        store.save(open);
        ChangeRequest merged = open.withStatus(Status.MERGED, T0.plusSeconds(90), "gonzalo");
        store.save(merged);
        assertThatThrownBy(() -> store.save(open.withStatus(Status.MERGED, T0.plusSeconds(95), "mateo")))
                .isInstanceOf(ConflictException.class).hasMessage("change request cr_1 is merged");
        assertThatThrownBy(() -> store.save(open.withStatus(Status.CLOSED, T0.plusSeconds(95), "mateo")))
                .isInstanceOf(ConflictException.class).hasMessage("change request cr_1 is merged");
        assertThat(store.get("cr_1")).contains(merged);
    }

    @Test
    void aMergeWhosePublishFailedIsOpenAgainAndCanBeMergedThen() {
        ChangeRequest open = cr("cr_1", T0);
        store.save(open);
        store.save(open.withStatus(Status.MERGED, T0.plusSeconds(90), "gonzalo"));
        store.save(open);
        assertThat(store.get("cr_1")).contains(open);
        ChangeRequest merged = open.withStatus(Status.MERGED, T0.plusSeconds(120), "gonzalo");
        store.save(merged);
        assertThat(store.get("cr_1")).contains(merged);
    }

    @Test
    void aChangeRequestSavedAlreadyMergedIsAdded() {
        ChangeRequest merged = cr("cr_demo01", T0).withStatus(Status.MERGED, T0.plusSeconds(90), "ana");
        store.save(merged);
        assertThat(store.all()).containsExactly(merged);
    }
}
