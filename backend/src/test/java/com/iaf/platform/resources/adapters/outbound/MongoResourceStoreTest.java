package com.iaf.platform.resources.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.outbound.ResourceStorePort;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoResourceStoreTest extends ResourceStoreContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    ResourceStorePort newStore(PlatformProperties props) {
        return seeded(db.mongo(), props);
    }

    @Override
    ResourceStorePort reopen(PlatformProperties props) {
        return newStore(props);
    }

    static MongoResourceStore seeded(Mongo mongo, PlatformProperties props) {
        MongoResourceStore store = new MongoResourceStore(mongo, props, CLOCK);
        store.seed();
        return store;
    }

    @Test
    void anotherInstanceSeesANewTypeAtOnce() {
        ResourceStorePort store = store(false);
        ResourceStorePort other = seeded(new Mongo(db.database()), props(SampleCatalog.ROOT.toString(), false));
        assertThat(other.types()).hasSize(5);
        ResourceTypeDefinition bucket = new ResourceTypeDefinition("bucket", "buckets", "team-storage", null, List.of("ready"), null, "Object storage", "team");
        store.saveType(bucket);
        assertThat(other.type("bucket")).contains(bucket);
        assertThat(other.types()).hasSize(6);
    }

    @Test
    void aRestartRewritesNoTypeTheFilesStillSay() {
        store(true);
        long revision = db.mongo().revision(MongoResourceStore.TYPES);
        assertThat(revision).as("the first start writes the types once").isEqualTo(1);
        ResourceStorePort after = reopen(props(SampleCatalog.ROOT.toString(), true));
        assertThat(db.mongo().revision(MongoResourceStore.TYPES)).as("every type reads back equal to its file").isEqualTo(revision);
        assertThat(after.type("image")).contains(SampleCatalog.type("image"));
    }
}
