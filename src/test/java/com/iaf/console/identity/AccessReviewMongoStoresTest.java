package com.iaf.console.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import com.iaf.console.identity.accessreviews.adapters.mongo.MongoAccessReviewItemStore;
import com.iaf.console.identity.accessreviews.adapters.mongo.MongoAccessReviewStore;
import com.iaf.console.identity.accessreviews.domain.AccessReview;
import com.iaf.console.identity.accessreviews.domain.AccessReviewItem;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import reactor.core.publisher.Mono;

/** The access reviews' MongoDB adapters against a real database (CI's mongo:7), in a database of their own. */
@EnabledIf("com.iaf.console.identity.MongoSupport#available")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessReviewMongoStoresTest {

    static final Instant NOW = Instant.parse("2026-10-23T10:00:00.123456789Z");
    static final String ACCOUNT = "123456789012";
    static final String OTHER = "210987654321";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "usr_test");
    static final AccountScope OTHER_SCOPE = AccountScope.of(OTHER, "global", "usr_test");

    MongoClient client;
    String database;
    Mongo mongo;

    @BeforeAll
    void connect() {
        client = MongoClients.create(MongoSupport.uri());
        database = MongoSupport.freshDatabase("console_identity_review_stores");
        mongo = new Mongo(client.getDatabase(database));
    }

    @AfterAll
    void drop() {
        Mono.from(client.getDatabase(database).drop()).block();
        client.close();
    }

    static AccessReview open(String id, String accountId, Instant startedAt, Duration dueIn) {
        return AccessReview.start(id, accountId, "Review " + id, AccessReview.Trigger.manual, "usr_admin", startedAt, startedAt.plus(dueIn),
                new AccessReview.Counts(2, 0, 0, 0, 2));
    }

    @Test
    void campaignsKeepOneOpenPerAccountAndPageTheNewestFirst() {
        MongoAccessReviewStore store = new MongoAccessReviewStore(mongo);
        store.ensureIndexes().block();
        store.ensureIndexes().block();

        AccessReview first = open("arev_1", ACCOUNT, NOW, Duration.ofDays(14));
        assertThat(store.insert(first).block()).isEqualTo(first);
        assertThatThrownBy(() -> store.insert(open("arev_2", ACCOUNT, NOW.plusSeconds(1), Duration.ofDays(14))).block()).as("one open per account")
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> store.insert(first).block()).as("the id exists").isInstanceOf(ConflictException.class);
        AccessReview other = open("arev_3", OTHER, NOW, Duration.ofDays(3));
        assertThat(store.insert(other).block()).isEqualTo(other);

        assertThat(store.find(SCOPE, "arev_1").block()).isEqualTo(first);
        assertThat(store.find(OTHER_SCOPE, "arev_1").block()).as("another account's").isNull();
        assertThat(store.find(SCOPE, "arev_2").block()).isNull();
        assertThat(store.findOpen(SCOPE).block()).isEqualTo(first);
        assertThat(store.findOpen(OTHER_SCOPE).block()).isEqualTo(other);

        AccessReview counted = first.counted(new AccessReview.Counts(2, 1, 0, 0, 1), NOW.plusSeconds(5));
        assertThat(store.update(counted, 1).block()).isEqualTo(counted);
        assertThat(store.update(first.cancelled(NOW.plusSeconds(6)), 1).block()).as("a stale version updates nothing").isNull();
        assertThat(store.update(new AccessReview("arev_1", OTHER, "x", AccessReview.Status.cancelled, AccessReview.Trigger.manual, "usr_admin", NOW, NOW,
                NOW, AccessReview.Counts.NONE, 3), 2).block()).as("nor another account's").isNull();
        AccessReview cancelled = counted.cancelled(NOW.plusSeconds(10));
        assertThat(store.update(cancelled, 2).block()).isEqualTo(cancelled);
        assertThat(store.find(SCOPE, "arev_1").block()).isEqualTo(cancelled);
        assertThat(store.findOpen(SCOPE).block()).isNull();

        // a cancelled or closed campaign leaves room for the next open one
        AccessReview second = AccessReview.start("arev_4", ACCOUNT, "Quarterly", AccessReview.Trigger.scheduled, "system:access-review", NOW.plusSeconds(20),
                NOW.plus(Duration.ofDays(1)), new AccessReview.Counts(1, 0, 0, 0, 1));
        assertThat(store.insert(second).block()).isEqualTo(second);
        AccessReview nothing = AccessReview.start("arev_5", ACCOUNT, "Nothing to review", AccessReview.Trigger.manual, "usr_admin", NOW.plusSeconds(20),
                NOW.plus(Duration.ofDays(1)), AccessReview.Counts.NONE);
        assertThat(nothing.status()).as("nothing to review: closed at once").isEqualTo(AccessReview.Status.closed);
        assertThat(store.insert(nothing).block()).isEqualTo(nothing);
        assertThat(store.findOpen(SCOPE).block()).isEqualTo(second);

        // started last first, the id breaking a tie
        assertThat(store.findLatest(SCOPE).block()).isEqualTo(nothing);
        assertThat(store.findLatest(AccountScope.of("300000000000", "global", "usr_test")).block()).isNull();
        Page<AccessReview> page1 = store.list(SCOPE, 1, null).block();
        assertThat(page1.items()).containsExactly(nothing);
        Page<AccessReview> page2 = store.list(SCOPE, 1, page1.next()).block();
        assertThat(page2.items()).containsExactly(second);
        Page<AccessReview> page3 = store.list(SCOPE, 1, page2.next()).block();
        assertThat(page3.items()).containsExactly(cancelled);
        assertThat(page3.hasNext()).isFalse();
        assertThat(store.list(SCOPE, 10, null).block().items()).containsExactly(nothing, second, cancelled);
        assertThatThrownBy(() -> store.list(SCOPE, 10, "nonsense").block()).isInstanceOf(IllegalArgumentException.class);

        // what is open and due, the earliest due first
        assertThat(store.findDue(NOW.plus(Duration.ofHours(1))).collectList().block()).isEmpty();
        assertThat(store.findDue(NOW.plus(Duration.ofDays(1))).collectList().block()).containsExactly(second);
        assertThat(store.findDue(NOW.plus(Duration.ofDays(3))).collectList().block()).containsExactly(second, other);
        AccessReview expired = second.expired(new AccessReview.Counts(1, 0, 0, 1, 0), NOW.plus(Duration.ofDays(1)));
        assertThat(store.update(expired, 1).block()).isEqualTo(expired);
        assertThat(store.findDue(NOW.plus(Duration.ofDays(3))).collectList().block()).containsExactly(other);
    }

    @Test
    void itemsPageInTheOrderTheyWereMadeAndCountByDecision() {
        MongoAccessReviewItemStore store = new MongoAccessReviewItemStore(mongo);
        store.ensureIndexes().block();
        store.ensureIndexes().block();

        AccessReviewItem a = AccessReviewItem.pending("arevi_1", ACCOUNT, "arev_1", "usr_a", "a@example.com", "A", "grp_owners", "owners",
                List.of("account-admin"));
        AccessReviewItem b = AccessReviewItem.pending("arevi_2", ACCOUNT, "arev_1", "usr_b", "b@example.com", null, "grp_members", "members",
                List.of("operator"));
        AccessReviewItem c = AccessReviewItem.pending("arevi_3", ACCOUNT, "arev_1", "usr_b", "b@example.com", null, "grp_viewers", "viewers", List.of());
        AccessReviewItem elsewhere = AccessReviewItem.pending("arevi_4", OTHER, "arev_9", "usr_c", "c@example.com", "C", "grp_x", "members",
                List.of("operator"));
        store.insertAll(List.of(a, b, c)).block();
        store.insertAll(List.of(elsewhere)).block();
        store.insertAll(List.of()).block();
        assertThatThrownBy(() -> store.insertAll(List.of(a)).block()).isInstanceOf(ConflictException.class);

        assertThat(store.find(SCOPE, "arev_1", "arevi_1").block()).isEqualTo(a);
        assertThat(store.find(SCOPE, "arev_1", "arevi_1").block().version()).as("the version comes back").isEqualTo(1);
        assertThat(store.find(OTHER_SCOPE, "arev_1", "arevi_1").block()).isNull();
        assertThat(store.find(SCOPE, "arev_2", "arevi_1").block()).as("another review's").isNull();

        AccessReviewItem kept = a.decided(AccessReviewItem.Decision.keep, "usr_admin", "needed", NOW);
        assertThat(store.update(kept, 1).block()).isEqualTo(kept);
        assertThat(store.update(a.decided(AccessReviewItem.Decision.revoke, "usr_admin", null, NOW), 1).block()).as("decided meanwhile").isNull();
        assertThat(store.find(SCOPE, "arev_1", "arevi_1").block()).isEqualTo(kept);
        assertThat(kept.version()).isEqualTo(2);
        AccessReviewItem reopened = store.update(kept.reopened(), 2).block();
        assertThat(reopened).isNotNull();
        assertThat(reopened.decision()).isEqualTo(AccessReviewItem.Decision.pending);
        assertThat(reopened.decidedBy()).isNull();
        AccessReviewItem revoked = store.update(reopened.decided(AccessReviewItem.Decision.revoke, "usr_admin", null, NOW.plusSeconds(1)), 3).block();
        assertThat(revoked).isNotNull();

        Page<AccessReviewItem> page1 = store.list(SCOPE, "arev_1", null, 2, null).block();
        assertThat(page1.items()).containsExactly(revoked, b);
        Page<AccessReviewItem> page2 = store.list(SCOPE, "arev_1", null, 2, page1.next()).block();
        assertThat(page2.items()).containsExactly(c);
        assertThat(page2.hasNext()).isFalse();
        assertThat(store.list(SCOPE, "arev_1", AccessReviewItem.Decision.pending, 10, null).block().items()).containsExactly(b, c);
        assertThat(store.list(SCOPE, "arev_1", AccessReviewItem.Decision.revoke, 10, null).block().items()).containsExactly(revoked);
        assertThat(store.list(SCOPE, "arev_1", AccessReviewItem.Decision.keep, 10, null).block().items()).isEmpty();
        assertThat(store.list(OTHER_SCOPE, "arev_1", null, 10, null).block().items()).isEmpty();
        assertThat(store.findPending(SCOPE, "arev_1").collectList().block()).containsExactly(b, c);
        assertThat(store.countByDecision(SCOPE, "arev_1").block())
                .isEqualTo(Map.of(AccessReviewItem.Decision.revoke, 1L, AccessReviewItem.Decision.pending, 2L));
        assertThat(AccessReview.Counts.of(store.countByDecision(SCOPE, "arev_1").block())).isEqualTo(new AccessReview.Counts(3, 0, 1, 0, 2));
        assertThat(store.countByDecision(SCOPE, "arev_none").block()).isEmpty();

        store.deleteByReview(SCOPE, "arev_1").block();
        assertThat(store.countByDecision(SCOPE, "arev_1").block()).isEmpty();
        assertThat(store.find(OTHER_SCOPE, "arev_9", "arevi_4").block()).as("another account's stay").isEqualTo(elsewhere);
    }
}
