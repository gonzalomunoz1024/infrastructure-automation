package com.iaf.console.state.inventory.adapters.mongo;

import static com.iaf.console.state.inventory.domain.Fixtures.REGION;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static com.iaf.console.state.inventory.domain.Fixtures.t;
import static org.assertj.core.api.Assertions.assertThat;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.state.inventory.adapters.ResourceStoreQueriesContract;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceDecider;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;

import reactor.core.publisher.Mono;

/** Phase 9's store reads on MongoDB, with its indexes, plus what only the documents show: which tombstones the TTL drops. */
@EnabledIf("com.iaf.console.state.inventory.adapters.mongo.MongoTestSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoResourceStoreQueriesTest extends ResourceStoreQueriesContract {

    private MongoClient client;
    private Mongo mongo;
    private MongoResourceEventStore events;
    private MongoResourceProjection projection;

    @BeforeAll
    void connect() {
        client = MongoTestSupport.client();
        mongo = new Mongo(client.getDatabase(MongoTestSupport.freshDatabase("console_state_queries")));
        events = new MongoResourceEventStore(mongo);
        projection = new MongoResourceProjection(mongo);
        events.ensureIndexes().block();
        projection.ensureIndexes().block();
    }

    @AfterAll
    void disconnect() {
        if (mongo != null) {
            MongoTestSupport.drop(mongo);
        }
        if (client != null) {
            client.close();
        }
    }

    @Override
    protected ResourceEventStorePort events() {
        return events;
    }

    @Override
    protected ResourceProjectionPort projection() {
        return projection;
    }

    @Test
    void aMovesTombstoneNeverExpiresWhileADeletionsLapses() {
        int base = 500_000;
        String account = "300000000011";
        String to = "300000000012";
        Resource moving = active(account, "vm", "ttl-moving", base, null, "vm-ttl-moving", null);
        Resource moved = fold(moving, ResourceDecider.transfer(moving, new Commands.Transfer(to, null, by("dana")),
                ResourceIdentity.mint("wf", "compute", REGION, to, "vm"), t(base + 10)), null);
        Resource deleted = delete(active(account, "vm", "ttl-deleted", base, null, "vm-ttl-deleted", null), base + 10);
        assertThat(projection.upsert(moved).block()).isTrue();
        assertThat(projection.upsert(deleted).block()).isTrue();

        Document move = raw(moved.wfrn());
        assertThat(move.get("expiresAt")).as("no TTL on a move's tombstone").isNull();
        assertThat(move.getString("transferredTo")).isEqualTo(moved.transferredTo());
        assertThat(move.getBoolean("live")).isFalse();
        Document deletion = raw(deleted.wfrn());
        assertThat(deletion.getDate("expiresAt")).as("a deletion's tombstone lapses").isEqualTo(Mongo.date(deleted.tombstoneUntil()));
        assertThat(deletion.containsKey("transferredTo")).isFalse();
    }

    private Document raw(String wfrn) {
        return Mono.from(mongo.collection(MongoResourceProjection.COLLECTION).find(Filters.eq("_id", wfrn)).first()).block();
    }
}
