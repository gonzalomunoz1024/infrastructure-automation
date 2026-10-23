package com.iaf.console.search.index.domain;

import static com.iaf.console.search.index.domain.Fixtures.T0;
import static com.iaf.console.search.index.domain.Fixtures.VM;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The fold of drift.findings: opened marks with a severity, closed clears, an unknown resource is read. */
class FindingEventsTest {

    private final IndexedResource doc = Fixtures.document(VM, "web-03", 4, Map.of(), T0);

    @Test
    void openedAndClosed() {
        IndexedResource drifted = ((EventOutcome.Applied) FindingEvents.apply(doc, Fixtures.finding("FindingOpened", VM, "f-1", 1, "warning", T0))).document();
        assertThat(drifted.drift()).isTrue();
        assertThat(drifted.driftSeverity()).isEqualTo("warning");
        assertThat(drifted.version()).as("the resource's version does not move on a finding").isEqualTo(4);
        IndexedResource worse = ((EventOutcome.Applied) FindingEvents.apply(drifted, Fixtures.finding("FindingUpdated", VM, "f-1", 2, "critical", T0)))
                .document();
        assertThat(worse.driftSeverity()).isEqualTo("critical");
        assertThat(FindingEvents.apply(worse, Fixtures.finding("FindingClosed", VM, "f-2", 1, null, T0))).as("another finding's closure")
                .isInstanceOf(EventOutcome.Ignored.class);
        IndexedResource clear = ((EventOutcome.Applied) FindingEvents.apply(worse, Fixtures.finding("FindingClosed", VM, "f-1", 3, null, T0))).document();
        assertThat(clear.drift()).isFalse();
        assertThat(clear.driftSeverity()).isNull();
    }

    @Test
    void aFindingOnAnUnknownResourceAsksForIt() {
        assertThat(FindingEvents.apply(null, Fixtures.finding("FindingOpened", VM, "f-1", 1, "info", T0))).isInstanceOf(EventOutcome.Refetch.class);
    }
}
