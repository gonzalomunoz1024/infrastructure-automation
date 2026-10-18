package com.iaf.platform.identity.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.common.adapters.mongo.Mongo;
import com.iaf.platform.common.adapters.mongo.TestMongo;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.identity.domain.ServiceAccount;
import com.iaf.platform.identity.ports.outbound.ServiceAccountStore;
import com.mongodb.MongoException;

@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoServiceAccountsTest extends ServiceAccountStoreContract {

    @RegisterExtension
    final TestMongo db = new TestMongo();

    @Override
    ServiceAccountStore newStore() {
        return new MongoServiceAccounts(db.mongo());
    }

    @Override
    ServiceAccountStore reopen() {
        return new MongoServiceAccounts(new Mongo(db.database()));
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
    void listsAccountsWithoutACreationTimeLast() {
        ServiceAccountStore store = newStore();
        ServiceAccount undatedB = account("sa_b", "iaf_b", null);
        ServiceAccount undatedA = account("sa_a", "iaf_a", null);
        ServiceAccount dated = account("sa_z", "iaf_z", NOW);
        store.save(undatedB);
        store.save(dated);
        store.save(undatedA);
        assertThat(store.all()).containsExactly(dated, undatedA, undatedB);
    }

    @Test
    void keepsNoTokenOnlyItsHash() {
        newStore().save(account("sa_1", "iaf_secret-one", NOW));
        List<Document> stored = db.database().getCollection(MongoServiceAccounts.COLLECTION).find().into(new ArrayList<>());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).toJson()).doesNotContain("iaf_secret-one");
        assertThat(stored.get(0).getString("tokenHash")).hasSize(64).isEqualTo(Hashes.sha256("iaf_secret-one"));
    }

    @Test
    void aTokenHashBelongsToOneAccountOnlyButAnyNumberHaveNone() {
        ServiceAccountStore store = newStore();
        store.save(account("sa_1", "iaf_shared", NOW));
        assertThatThrownBy(() -> store.save(account("sa_2", "iaf_shared", NOW)))
                .isInstanceOfSatisfying(MongoException.class, e -> assertThat(Mongo.duplicateKey(e)).isTrue());

        store.save(new ServiceAccount("sa_3", "no token", List.of(), List.of(), null, NOW));
        store.save(new ServiceAccount("sa_4", "no token either", List.of(), List.of(), null, NOW));
        assertThat(store.all()).extracting(ServiceAccount::id).containsExactly("sa_1", "sa_3", "sa_4");
        assertThat(store.byTokenHash(Hashes.sha256("iaf_shared"))).map(ServiceAccount::id).contains("sa_1");
    }
}
