package com.iaf.console.drift.rightsizing.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationAction;
import com.iaf.console.drift.rightsizing.domain.RecommendationKind;
import com.iaf.console.drift.rightsizing.domain.RecommendationQuery;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.domain.ResourceUtilisation;
import com.iaf.console.drift.rightsizing.domain.RightsizingRules;
import com.iaf.console.drift.rightsizing.domain.Utilisation;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.drift.rightsizing.ports.outbound.UtilisationStorePort;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

/**
 * What every recommendation and utilisation store does, in memory and in MongoDB alike: writes
 * conditional on the revision, one current recommendation per resource, the largest saving first
 * (unpriced ones last), scoped reads, and each resource's last utilisation kept in place.
 */
public abstract class RecommendationStoreContract {

    protected static final String ACCOUNT = "123456789012";
    protected static final String OTHER_ACCOUNT = "210987654321";
    protected static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    protected static final AccountScope OTHER = AccountScope.of(OTHER_ACCOUNT, "global", "ana");
    protected static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    private RecommendationStorePort store;
    private UtilisationStorePort utilisation;

    /** An empty recommendation store. */
    protected abstract RecommendationStorePort freshRecommendations();

    /** An empty utilisation store. */
    protected abstract UtilisationStorePort freshUtilisation();

    @BeforeEach
    void empty() {
        store = freshRecommendations();
        utilisation = freshUtilisation();
    }

    protected static String wfrn(String accountId, String id) {
        return "wfrn:wf:compute:global:" + accountId + ":vm/" + id;
    }

    private static ObjectNode size(int cpu, int memoryGb) {
        return Json.object().put("cpu", cpu).put("memoryGb", memoryGb);
    }

    private static Utilisation used() {
        return new Utilisation(T0.minusSeconds(14 * 86_400), T0, 336, 12.5, 30.0, 20.25, 41.0);
    }

    /** An open recommendation: a resize saving {@code saving} a month, or unpriced when it is null. */
    protected static Recommendation open(String id, String accountId, String resource, Double saving) {
        RightsizingRules.Verdict verdict = new RightsizingRules.Verdict(RecommendationKind.OVERSIZED, RecommendationAction.RESIZE, size(4, 16),
                size(2, 8));
        Money cost = saving == null ? null : Money.of(saving * 2, "USD", "list-2026");
        Money save = saving == null ? null : Money.of(saving, "USD", "list-2026");
        return Recommendation.open(id, wfrn(accountId, resource), accountId, "global", "vm", resource, verdict, used(), cost, save, T0);
    }

    protected static Recommendation idle(String id, String accountId, String resource, double saving) {
        RightsizingRules.Verdict verdict = new RightsizingRules.Verdict(RecommendationKind.IDLE, RecommendationAction.DELETE, size(2, 8), null);
        return Recommendation.open(id, wfrn(accountId, resource), accountId, "global", "vm", resource, verdict, used(),
                Money.of(saving, "USD", null), Money.of(saving, "USD", null), T0);
    }

    @Test
    void aRecommendationKeepsEverythingItSays() {
        Recommendation saved = store.save(open("rec-a", ACCOUNT, "vm-a", 40.5)).block();
        assertThat(saved.rev()).isEqualTo(1);
        Recommendation read = store.find(SCOPE, "rec-a").block();
        assertThat(read).isEqualTo(saved);
        assertThat(read.monthlySaving().amount()).isEqualByComparingTo(new BigDecimal("40.50"));
        assertThat(read.monthlySaving().basis()).isEqualTo("list-2026");
        assertThat(read.monthlyCost().amount()).isEqualByComparingTo("81");
        assertThat(read.proposed().get("cpu").asInt()).isEqualTo(2);
        assertThat(read.utilisation().memoryP95()).isEqualTo(20.25);
        assertThat(read.kind()).isEqualTo(RecommendationKind.OVERSIZED);
        assertThat(store.find(OTHER, "rec-a").block()).as("another account's").isNull();
        assertThat(store.findAny("rec-a").block()).isEqualTo(saved);

        Recommendation dismissed = store.save(read.dismissed("dana", "later", T0.plusSeconds(86_400), T0.plusSeconds(60))).block();
        assertThat(store.find(SCOPE, "rec-a").block().dismissedUntil()).isEqualTo(T0.plusSeconds(86_400));
        assertThat(dismissed.rev()).isEqualTo(2);
    }

    @Test
    void aWriteFromAnOlderReadIsAConflict() {
        Recommendation saved = store.save(open("rec-a", ACCOUNT, "vm-a", 40.0)).block();
        store.save(saved.dismissed("dana", null, null, T0.plusSeconds(1))).block();
        assertThatThrownBy(() -> store.save(saved.superseded(T0.plusSeconds(2))).block()).isInstanceOf(RecommendationStorePort.Conflict.class);
        assertThatThrownBy(() -> store.save(open("rec-a", ACCOUNT, "vm-a", 40.0)).block()).as("created twice")
                .isInstanceOf(RecommendationStorePort.Conflict.class);
        assertThat(store.find(SCOPE, "rec-a").block().status()).isEqualTo(RecommendationStatus.DISMISSED);
    }

    @Test
    void aResourceHasOneCurrentRecommendation() {
        Recommendation first = store.save(open("rec-1", ACCOUNT, "vm-a", 40.0)).block();
        assertThatThrownBy(() -> store.save(idle("rec-2", ACCOUNT, "vm-a", 60)).block()).isInstanceOf(RecommendationStorePort.Conflict.class);
        assertThat(store.findCurrentByWfrn(wfrn(ACCOUNT, "vm-a")).block().recommendationId()).isEqualTo("rec-1");
        // a dismissed one is still current
        Recommendation dismissed = store.save(first.dismissed("dana", null, null, T0.plusSeconds(1))).block();
        assertThatThrownBy(() -> store.save(idle("rec-2", ACCOUNT, "vm-a", 60)).block()).isInstanceOf(RecommendationStorePort.Conflict.class);
        // once it is superseded, another may open
        store.save(dismissed.superseded(T0.plusSeconds(2))).block();
        assertThat(store.findCurrentByWfrn(wfrn(ACCOUNT, "vm-a")).block()).isNull();
        store.save(idle("rec-2", ACCOUNT, "vm-a", 60)).block();
        assertThat(store.findCurrentByWfrn(wfrn(ACCOUNT, "vm-a")).block().recommendationId()).isEqualTo("rec-2");
        assertThat(store.find(SCOPE, "rec-1").block().status()).isEqualTo(RecommendationStatus.SUPERSEDED);
    }

    @Test
    void aPageListsTheLargestSavingFirstAndUnpricedOnesLast() {
        store.save(open("rec-1", ACCOUNT, "vm-1", 40.0)).block();
        store.save(idle("rec-2", ACCOUNT, "vm-2", 60)).block();
        store.save(open("rec-3", ACCOUNT, "vm-3", null)).block();
        store.save(idle("rec-4", ACCOUNT, "vm-4", 60)).block();
        Recommendation applied = store.save(open("rec-5", ACCOUNT, "vm-5", 100.0)).block();
        store.save(applied.applied("job-5", "dana", null, T0.plusSeconds(5))).block();
        store.save(open("rec-6", OTHER_ACCOUNT, "vm-6", 500.0)).block();

        RecommendationQuery open = new RecommendationQuery(null, null, RecommendationStatus.OPEN, null, 50, null);
        assertThat(ids(store.list(SCOPE, open).block())).containsExactly("rec-4", "rec-2", "rec-1", "rec-3");
        assertThat(ids(store.list(SCOPE, new RecommendationQuery(null, RecommendationKind.IDLE, RecommendationStatus.OPEN, null, 50, null)).block()))
                .containsExactly("rec-4", "rec-2");
        assertThat(ids(store.list(SCOPE, new RecommendationQuery(null, null, RecommendationStatus.APPLIED, null, 50, null)).block()))
                .containsExactly("rec-5");
        assertThat(ids(store.list(SCOPE, new RecommendationQuery("vm", null, null, wfrn(ACCOUNT, "vm-3"), 50, null)).block())).containsExactly("rec-3");
        assertThat(ids(store.list(SCOPE, new RecommendationQuery("database", null, null, null, 50, null)).block())).isEmpty();

        Page<Recommendation> first = store.list(SCOPE, new RecommendationQuery(null, null, RecommendationStatus.OPEN, null, 2, null)).block();
        assertThat(ids(first)).containsExactly("rec-4", "rec-2");
        Page<Recommendation> second = store.list(SCOPE, new RecommendationQuery(null, null, RecommendationStatus.OPEN, null, 2,
                CursorPaging.decode(first.next()))).block();
        assertThat(ids(second)).containsExactly("rec-1", "rec-3");
        assertThat(second.hasNext()).isFalse();

        assertThat(store.findOpen(SCOPE).map(Recommendation::recommendationId).collectList().block()).containsExactly("rec-4", "rec-2", "rec-1", "rec-3");
        assertThat(store.findCurrent(ACCOUNT).map(Recommendation::recommendationId).collectList().block())
                .containsExactly("rec-1", "rec-2", "rec-3", "rec-4");
        assertThat(store.findCurrent(null).map(Recommendation::recommendationId).collectList().block()).contains("rec-6").hasSize(5);
    }

    @Test
    void eachResourcesLastUtilisationIsKeptInPlace() {
        ResourceUtilisation first = new ResourceUtilisation(wfrn(ACCOUNT, "vm-a"), ACCOUNT, "global", "vm", size(4, 16), null, used(), T0);
        utilisation.save(first).block();
        assertThat(utilisation.find(wfrn(ACCOUNT, "vm-a")).block()).isEqualTo(first);

        ResourceUtilisation resized = new ResourceUtilisation(wfrn(ACCOUNT, "vm-a"), ACCOUNT, "global", "vm", size(2, 8), T0.plusSeconds(3600),
                new Utilisation(T0.plusSeconds(3600), T0.plusSeconds(7200), 0, null, null, null, null), T0.plusSeconds(7200));
        utilisation.save(resized).block();
        assertThat(utilisation.find(wfrn(ACCOUNT, "vm-a")).block()).isEqualTo(resized);
        utilisation.save(new ResourceUtilisation(wfrn(ACCOUNT, "vm-b"), ACCOUNT, "global", "vm", size(2, 8), null, used(), T0.plusSeconds(60))).block();
        utilisation.save(new ResourceUtilisation(wfrn(OTHER_ACCOUNT, "vm-c"), OTHER_ACCOUNT, "global", "vm", size(2, 8), null, used(),
                T0.plusSeconds(9000))).block();

        assertThat(utilisation.findLastReadAt(SCOPE).block()).isEqualTo(T0.plusSeconds(7200));
        utilisation.delete(wfrn(ACCOUNT, "vm-a")).block();
        assertThat(utilisation.find(wfrn(ACCOUNT, "vm-a")).block()).isNull();
        assertThat(utilisation.findLastReadAt(SCOPE).block()).isEqualTo(T0.plusSeconds(60));
        assertThat(utilisation.findLastReadAt(AccountScope.of("111111111111", "global", "x")).block()).isNull();
    }

    private static List<String> ids(Page<Recommendation> page) {
        return page.items().stream().map(Recommendation::recommendationId).toList();
    }
}
