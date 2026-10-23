package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.identity.accounts.adapters.mongo.MongoAccountStore;
import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.principals.adapters.mongo.MongoMembershipStore;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;
import com.iaf.console.identity.principals.domain.UserStatus;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/**
 * The lifecycle's new fields on MongoDB: a membership's derived and excluded groups and an
 * account's cost centre, closure and owning-team mark survive a round trip, and documents stored
 * before the fields existed read as if they were empty.
 */
@EnabledIf("com.iaf.console.identity.MongoSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoLifecycleStoresTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    static final String ACCOUNT = "123456789012";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "usr_test");

    MongoClient client;
    String database;
    Mongo mongo;

    @BeforeAll
    void connect() {
        client = MongoClients.create(MongoSupport.uri());
        database = MongoSupport.freshDatabase("console_identity_lifecycle_stores");
        mongo = new Mongo(client.getDatabase(database));
    }

    @AfterAll
    void drop() {
        Mono.from(client.getDatabase(database).drop()).block();
        client.close();
    }

    @Test
    void aMembershipKeepsItsDerivedAndExcludedGroups() {
        MongoMembershipStore store = new MongoMembershipStore(mongo);
        store.ensureIndexes().block();
        User dana = User.create("usr_dana", "dana@example.com", "Dana", "https://idp", "dana", NOW);
        Membership mapped = Membership.mapped(ACCOUNT, dana, List.of("grp_members", "grp_sre"), NOW);
        store.insert(mapped).block();
        assertThat(store.find(SCOPE, "usr_dana").block()).isEqualTo(mapped);

        Membership changed = mapped.regrouped(List.of("grp_sre", "grp_viewers"), Set.of(), NOW.plusSeconds(1));
        assertThat(store.update(changed, 1).block()).isEqualTo(changed);
        Membership read = store.find(SCOPE, "usr_dana").block();
        assertThat(read).isEqualTo(changed);
        assertThat(read.idpGroupIds()).containsExactly("grp_sre");
        assertThat(read.excludedGroupIds()).containsExactly("grp_members");
        assertThat(store.findByGroup(SCOPE, "grp_sre").collectList().block()).containsExactly(changed);

        Membership.Synced synced = read.synced(List.of("grp_members"), Set.of(), NOW.plusSeconds(2));
        assertThat(synced.changed()).as("excluded, and sre is gone").isTrue();
        assertThat(store.update(synced.membership(), 2).block()).isEqualTo(synced.membership());
        assertThat(store.find(SCOPE, "usr_dana").block().groupIds()).containsExactly("grp_viewers");
    }

    @Test
    void aMembershipStoredWithoutTheListsHoldsEveryGroupAsTheAdmins() {
        MongoMembershipStore store = new MongoMembershipStore(mongo);
        User mia = User.create("usr_mia", "mia@example.com", "Mia", null, null, NOW);
        Membership current = Membership.create(ACCOUNT, mia, List.of("grp_members"), NOW);
        Document record = mongo.doc(current);
        record.remove("idpGroupIds");
        record.remove("excludedGroupIds");
        Document old = new Document("_id", ACCOUNT + ":usr_mia").append("accountId", ACCOUNT).append("userId", "usr_mia").append("email", "mia@example.com")
                .append("name", "Mia").append("status", "active").append("groupIds", List.of("grp_members")).append("version", 1L)
                .append(Mongo.DOC, record);
        Mono.from(mongo.collection("memberships").insertOne(old)).block();

        Membership read = store.find(SCOPE, "usr_mia").block();
        assertThat(read).isEqualTo(current);
        assertThat(read.idpGroupIds()).isEmpty();
        assertThat(read.excludedGroupIds()).isEmpty();
        assertThat(read.synced(List.of(), Set.of(), NOW).changed()).as("no sync removes what nobody knows the provider gave").isFalse();
        Membership again = read.changed("Mia C", UserStatus.active, null, Set.of(), NOW.plusSeconds(1));
        assertThat(store.update(again, 1).block()).isEqualTo(again);
        assertThat(store.find(SCOPE, "usr_mia").block().name()).isEqualTo("Mia C");
    }

    @Test
    void anAccountKeepsItsCostCentreClosureAndMark() {
        MongoAccountStore store = new MongoAccountStore(mongo);
        store.ensureIndexes().block();
        Account a = Account.create(ACCOUNT, "Compute", "team-compute", "global", "sa-1", " cc-1234 ", NOW);
        assertThat(a.costCentre()).isEqualTo("cc-1234");
        store.insert(a).block();
        assertThat(store.findById(ACCOUNT).block()).isEqualTo(a);

        Account marked = a.withOwningTeamMissing(true, NOW.plusSeconds(1));
        assertThat(store.update(marked, 1).block()).isEqualTo(marked);
        Account closed = marked.closed("moved to the new platform", NOW.plusSeconds(2));
        assertThat(store.update(closed, 2).block()).isEqualTo(closed);
        Account read = store.findById(ACCOUNT).block();
        assertThat(read).isEqualTo(closed);
        assertThat(read.status()).isEqualTo(AccountStatus.closed);
        assertThat(read.closedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(read.closeReason()).isEqualTo("moved to the new platform");
        assertThat(read.owningTeamIsMissing()).isTrue();
        assertThat(store.list(AccountStatus.closed, 10, null).block().items()).containsExactly(closed);

        Account uncharged = read.changed(null, null, null, "", NOW.plusSeconds(3));
        assertThat(store.update(uncharged, 3).block().costCentre()).as("an empty cost centre clears it").isNull();
        Account unmarked = uncharged.withOwningTeamMissing(false, NOW.plusSeconds(4));
        assertThat(store.update(unmarked, 4).block()).isEqualTo(unmarked);
        assertThat(store.findById(ACCOUNT).block().owningTeamMissing()).as("a cleared mark is absent").isNull();
        assertThat(store.update(unmarked, 4).block()).as("a stale version updates nothing").isNull();
    }

    @Test
    void anAccountStoredWithoutTheNewFieldsReadsWithoutThem() {
        MongoAccountStore store = new MongoAccountStore(mongo);
        Account plain = Account.create("210987654321", "Data", "team-data", "global", "sa-2", NOW);
        Document record = mongo.doc(plain);
        assertThat(record.keySet()).doesNotContain("costCentre", "closedAt", "closeReason", "owningTeamMissing");
        Document old = new Document("_id", "210987654321").append("name", "Data").append("status", "active").append("owningTeam", "team-data")
                .append("version", 1L).append("createdAt", Mongo.date(NOW)).append(Mongo.DOC, record);
        Mono.from(mongo.collection("accounts").insertOne(old)).block();
        Account read = store.findById("210987654321").block();
        assertThat(read).isEqualTo(plain);
        assertThat(read.costCentre()).isNull();
        assertThat(read.owningTeamMissing()).isNull();
    }
}
