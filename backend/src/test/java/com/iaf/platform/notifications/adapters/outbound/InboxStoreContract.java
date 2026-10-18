package com.iaf.platform.notifications.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.InboxStore;

/**
 * What every {@link InboxStore} does, run against the in-memory store and the MongoDB one (when
 * {@code PLATFORM_TEST_MONGO_URI} names a server).
 */
abstract class InboxStoreContract {

    static final Instant T0 = Instant.parse("2026-10-18T09:15:30.123456789Z");
    static final Identity ANA = new Identity("ana", "Ana", "ana@example.com", List.of("team-data", "data-approvers"), List.of("ops"), false,
            Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", "mateo@example.com", List.of("team-compute"), List.of("ops"), false,
            Identity.Kind.USER);
    static final Identity GUEST = new Identity("guest", "Guest", null, List.of(), List.of(), false, Identity.Kind.USER);

    InboxStore store;

    /** An empty store keeping at most {@code keep} notifications. */
    abstract InboxStore newStore(int keep);

    @BeforeEach
    void setUp() {
        store = newStore(100);
    }

    static Notification to(String id, List<String> users, List<String> teams) {
        return Notification.builder().id(id).at(T0).kind(Notification.NOTIFY).severity("warning").title("vm-restart: " + id)
                .message("web-01 restarted").link("/jobs/job_1").channel("#vm-ops").users(users).teams(teams).jobId("job_1").workflow("vm-restart")
                .build();
    }

    @Test
    void readsANotificationBackExactlyAsSaved() {
        Notification n = to("ntf_1", List.of("ana"), List.of("team-compute"));
        store.save(n);

        assertThat(store.get("ntf_1")).contains(n);
        assertThat(store.get("ntf_9")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    void theFirstSaveOfAnIdWins() {
        store.save(to("ntf_1", List.of("ana"), List.of()));
        store.save(to("ntf_1", List.of("mateo"), List.of()).withTitle("changed"));

        assertThat(store.get("ntf_1")).map(Notification::title).contains("vm-restart: ntf_1");
        assertThat(store.visibleTo(MATEO)).isEmpty();
    }

    @Test
    void showsEachPersonWhatIsAddressedToThemOrTheirTeamsNewestFirst() {
        Notification toAna = to("ntf_1", List.of("ana"), List.of());
        Notification toCompute = to("ntf_2", List.of(), List.of("team-compute"));
        Notification toApprovers = to("ntf_3", List.of(), List.of("data-approvers", "team-compute"));
        Notification toMateo = to("ntf_4", List.of("mateo"), List.of());
        Notification toNobody = to("ntf_5", List.of(), List.of());
        for (Notification n : List.of(toAna, toCompute, toApprovers, toMateo, toNobody)) {
            store.save(n);
        }

        assertThat(store.visibleTo(ANA)).containsExactly(toApprovers, toAna);
        assertThat(store.visibleTo(MATEO)).containsExactly(toMateo, toApprovers, toCompute);
        assertThat(store.visibleTo(GUEST)).isEmpty();
        assertThat(store.visibleTo(null)).isEmpty();
        assertThat(store.get("ntf_5")).contains(toNobody);
    }

    @Test
    void marksReadPerPersonAndSaysWhenItIsNotKept() {
        store.save(to("ntf_1", List.of(), List.of("team-data")));

        assertThat(store.readBy("ntf_1", "ana")).isFalse();
        assertThat(store.markRead("ntf_1", "ana")).isTrue();
        assertThat(store.markRead("ntf_1", "ana")).as("reading it again changes nothing").isTrue();
        assertThat(store.readBy("ntf_1", "ana")).isTrue();
        assertThat(store.readBy("ntf_1", "mateo")).isFalse();
        assertThat(store.markRead("ntf_9", "ana")).isFalse();
        assertThat(store.markRead(null, "ana")).isFalse();
        assertThat(store.readBy("ntf_9", "ana")).isFalse();
    }

    @Test
    void keepsTheNewestOnlyAndForgetsTheReadersOfTheDropped() {
        InboxStore small = newStore(2);
        Notification first = to("ntf_1", List.of("ana"), List.of());
        Notification second = to("ntf_2", List.of("ana"), List.of());
        Notification third = to("ntf_3", List.of("ana"), List.of());
        small.save(first);
        small.save(second);
        assertThat(small.markRead("ntf_1", "ana")).isTrue();

        small.save(third);

        assertThat(small.get("ntf_1")).isEmpty();
        assertThat(small.visibleTo(ANA)).containsExactly(third, second);
        assertThat(small.readBy("ntf_1", "ana")).isFalse();
        assertThat(small.markRead("ntf_1", "ana")).isFalse();
    }
}
