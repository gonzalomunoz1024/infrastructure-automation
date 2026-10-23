package com.iaf.console.search.index.domain;

import static com.iaf.console.search.index.domain.Fixtures.T0;
import static com.iaf.console.search.index.domain.Fixtures.VM;
import static com.iaf.console.search.index.domain.Fixtures.VM_HINTS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.events.EventEnvelope;

/** The fold of state.resources: versions gate, first sights open, gaps ask, tombstones close. */
class ResourceEventsTest {

    @Test
    void aRequestOpensAPendingDocumentWithItsSearchableInput() {
        EventOutcome outcome = ResourceEvents.apply(null, Fixtures.requested(VM, "web-03", Map.of("name", "web-03", "image", "img-1", "environment", "dev")),
                VM_HINTS);
        IndexedResource doc = ((EventOutcome.Applied) outcome).document();
        assertThat(doc.wfrn()).isEqualTo(VM);
        assertThat(doc.accountId()).isEqualTo(Fixtures.ACCOUNT);
        assertThat(doc.type()).isEqualTo("vm");
        assertThat(doc.service()).isEqualTo("compute");
        assertThat(doc.name()).isEqualTo("web-03");
        assertThat(doc.state()).isEqualTo(ResourceState.PENDING);
        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.fields()).containsEntry("environment", "dev").containsEntry("name", "web-03").doesNotContainKey("image");
        assertThat(doc.createdAt()).isEqualTo(T0);
        assertThat(doc.workflow()).isEqualTo("create-vm");
    }

    @Test
    void theNextVersionAppliesAndAnOlderOneIsIgnored() {
        IndexedResource v1 = applied(ResourceEvents.apply(null, Fixtures.requested(VM, "web-03", Map.of()), VM_HINTS));
        EventEnvelope started = Fixtures.resourceEvent("ProvisioningStarted", VM, 2, "pending", T0.plusSeconds(1), Fixtures.data().put("jobId", "job-1"));
        IndexedResource v2 = applied(ResourceEvents.apply(v1, started, VM_HINTS));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.lastJobId()).isEqualTo("job-1");
        assertThat(v2.updatedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(ResourceEvents.apply(v2, started, VM_HINTS)).isInstanceOf(EventOutcome.Ignored.class);
        assertThat(ResourceEvents.apply(v2, Fixtures.requested(VM, "web-03", Map.of()), VM_HINTS)).isInstanceOf(EventOutcome.Ignored.class);
    }

    @Test
    void aGapOrAFirstSightMidLifeAsksForTheWholeResource() {
        IndexedResource v1 = applied(ResourceEvents.apply(null, Fixtures.requested(VM, "web-03", Map.of()), VM_HINTS));
        EventEnvelope v3 = Fixtures.succeeded(VM, 3, Map.of("name", "web-03", "environment", "prod"), T0.plusSeconds(5));
        assertThat(ResourceEvents.apply(v1, v3, VM_HINTS)).isInstanceOf(EventOutcome.Refetch.class);
        assertThat(ResourceEvents.apply(null, v3, VM_HINTS)).isInstanceOf(EventOutcome.Refetch.class);
    }

    @Test
    void successReplacesTheFieldsAndTagsAccumulate() {
        IndexedResource v1 = applied(ResourceEvents.apply(null, Fixtures.requested(VM, "web-03", Map.of("environment", "dev")), VM_HINTS));
        IndexedResource v2 = applied(ResourceEvents.apply(v1, Fixtures.succeeded(VM, 2, Map.of("name", "web-03", "environment", "prod", "cpu", 4),
                T0.plusSeconds(2)), VM_HINTS));
        assertThat(v2.state()).isEqualTo(ResourceState.ACTIVE);
        assertThat(v2.fields()).containsEntry("environment", "prod").doesNotContainKey("cpu");
        IndexedResource v3 = applied(ResourceEvents.apply(v2, Fixtures.tagged(VM, 3, Map.of("env", "prod", "team", "web"), List.of(), T0.plusSeconds(3)),
                VM_HINTS));
        assertThat(v3.tags()).containsEntry("env", "prod").containsEntry("team", "web");
        IndexedResource v4 = applied(ResourceEvents.apply(v3, Fixtures.tagged(VM, 4, Map.of(), List.of("team"), T0.plusSeconds(4)), VM_HINTS));
        assertThat(v4.tags()).containsOnlyKeys("env");
        assertThat(v4.tagsFlat()).containsExactly("env=prod");
    }

    @Test
    void driftMarksAndClears() {
        IndexedResource v1 = applied(ResourceEvents.apply(null, Fixtures.requested(VM, "web-03", Map.of()), VM_HINTS));
        IndexedResource v2 = applied(ResourceEvents.apply(v1, Fixtures.resourceEvent("DriftDetected", VM, 2, "active", T0.plusSeconds(2),
                Fixtures.data().put("findingId", "f-1")), VM_HINTS));
        assertThat(v2.drift()).isTrue();
        assertThat(v2.driftFindingId()).isEqualTo("f-1");
        IndexedResource v3 = applied(ResourceEvents.apply(v2, Fixtures.resourceEvent("DriftResolved", VM, 3, "active", T0.plusSeconds(3),
                Fixtures.data().put("findingId", "f-1").put("resolution", "reverted")), VM_HINTS));
        assertThat(v3.drift()).isFalse();
        assertThat(v3.driftFindingId()).isNull();
    }

    @Test
    void deletedIsATombstoneWhateverWasSeen() {
        EventEnvelope deleted = Fixtures.deleted(VM, 7, T0.plus(Duration.ofHours(1)));
        IndexedResource fromNothing = applied(ResourceEvents.apply(null, deleted, VM_HINTS));
        assertThat(fromNothing.state()).isEqualTo(ResourceState.DELETED);
        assertThat(fromNothing.deletedAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
        assertThat(fromNothing.tombstoneUntil()).isAfter(fromNothing.deletedAt());
        assertThat(fromNothing.version()).isEqualTo(7);
        IndexedResource v1 = applied(ResourceEvents.apply(null, Fixtures.requested(VM, "web-03", Map.of()), VM_HINTS));
        IndexedResource tombstone = applied(ResourceEvents.apply(v1, deleted, VM_HINTS));
        assertThat(tombstone.name()).as("the name stays findable for the tombstone's life").isEqualTo("web-03");
        assertThat(tombstone.deleted()).isTrue();
    }

    @Test
    void sensitiveValuesNeverEnterTheIndex() {
        EventEnvelope requested = Fixtures.resourceEvent("ResourceRequested", Fixtures.DB, 1, "pending", T0,
                Fixtures.data().put("name", "orders").set("input", Fixtures.data().put("name", "orders").put("secret", "hunter2").put("password", "x")
                        .put("environment", "dev")));
        IndexedResource doc = applied(ResourceEvents.apply(null, requested, Fixtures.DB_HINTS));
        assertThat(doc.fields()).containsOnlyKeys("name", "environment");
        assertThat(doc.text()).doesNotContain("hunter2").doesNotContain("x ");
        EventEnvelope masked = Fixtures.succeeded(Fixtures.DB, 2, Map.of("name", "orders", "environment", "***"), T0.plusSeconds(1));
        IndexedResource v2 = applied(ResourceEvents.apply(doc, masked, Fixtures.DB_HINTS));
        assertThat(v2.fields()).as("a masked value is dropped, not indexed as ***").doesNotContainKey("environment");
    }

    @Test
    void anUnknownTypeStillGetsItsNameAndState() {
        EventEnvelope e = Fixtures.resourceEvent("ResourceRequested", VM, 1, "pending", T0, Fixtures.data().put("name", "x").set("input",
                Fixtures.data().put("environment", "dev")));
        IndexedResource doc = applied(ResourceEvents.apply(null, e, null));
        assertThat(doc.name()).isEqualTo("x");
        assertThat(doc.fields()).isEmpty();
        assertThat(doc.service()).isEqualTo("compute");
    }

    @Test
    void anEventWithoutAVersionOrAStateIsNotApplied() {
        EventEnvelope noVersion = EventEnvelope.of("e", "k", "Tagged", T0, "resource", VM, "dana", Fixtures.data())
                .withContext(new EventEnvelope.Context(Fixtures.ACCOUNT, "global", VM, null), null);
        assertThat(ResourceEvents.apply(null, noVersion, VM_HINTS)).isInstanceOf(EventOutcome.Ignored.class);
        EventEnvelope noState = EventEnvelope.of("e", "k", "Tagged", T0, "resource", VM, "dana", Fixtures.data().put("version", 1))
                .withContext(new EventEnvelope.Context(Fixtures.ACCOUNT, "global", VM, null), null);
        assertThat(ResourceEvents.apply(null, noState, VM_HINTS)).isInstanceOf(EventOutcome.Refetch.class);
    }

    private static IndexedResource applied(EventOutcome outcome) {
        assertThat(outcome).isInstanceOf(EventOutcome.Applied.class);
        return ((EventOutcome.Applied) outcome).document();
    }
}
