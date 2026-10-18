package com.iaf.platform.releases.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoReleaseStoreTest extends ReleaseStoreContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    ReleaseStore newStore() {
        return new MongoReleaseStore(db.mongo());
    }

    @Override
    ReleaseStore reopen() {
        return new MongoReleaseStore(db.mongo());
    }

    @Test
    void anotherInstanceSeesAReleaseMoveOnAtOnce() {
        ReleaseStore other = new MongoReleaseStore(new Mongo(db.database()));
        Release canary = store.save(canary("rel_1", "vm-restart", NOW));
        assertThat(other.active("vm-restart")).contains(canary);
        Release promoted = other.save(canary.promoted(NOW.plusSeconds(60), "promoted: every gate passed", "platform"));
        assertThat(store.active("vm-restart")).isEmpty();
        assertThat(store.find("rel_1")).contains(promoted);
        assertThat(db.mongo().collection(MongoReleaseStore.COLLECTION).countDocuments()).isEqualTo(1);
    }
}
