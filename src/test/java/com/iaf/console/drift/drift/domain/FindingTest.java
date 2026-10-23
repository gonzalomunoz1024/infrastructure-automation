package com.iaf.console.drift.drift.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class FindingTest {

    private static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    private static final List<FindingField> FIELDS = List.of(FindingField.of("cpu", Json.MAPPER.valueToTree(2), Json.MAPPER.valueToTree(4)));

    private static Finding opened() {
        return Finding.open("fnd-1", "wfrn:wf:compute:global:123456789012:vm/vm-1", "123456789012", "global", "vm", Severity.WARNING, FIELDS, T0);
    }

    @Test
    void everyChangeIsANewVersionAndASameReadIsNot() {
        Finding f = opened();
        assertThat(f.version()).isEqualTo(1);
        assertThat(f.seen(T0.plusSeconds(60)).version()).isEqualTo(1);
        assertThat(f.changed(FIELDS, Severity.CRITICAL, T0.plusSeconds(60)).version()).isEqualTo(2);
        Finding running = f.reverting("job-1", "dana", "put it back", T0.plusSeconds(60));
        assertThat(running.version()).isEqualTo(2);
        assertThat(running.reverting()).isTrue();
        assertThat(running.revertEnded(RevertStatus.FAILED).reverting()).isFalse();
    }

    @Test
    void aRevertThatClosesTheFindingSucceeded() {
        Finding closed = opened().reverting("job-1", "dana", null, T0).closed(Resolution.REVERTED, "dana", null, T0.plusSeconds(30), null);
        assertThat(closed.open()).isFalse();
        assertThat(closed.resolution()).isEqualTo(Resolution.REVERTED);
        assertThat(closed.revertStatus()).isEqualTo(RevertStatus.SUCCEEDED);
    }

    @Test
    void aStoreThatAnsweredSomethingElseDidNotKeepTheWrite() {
        Finding open = opened();
        Finding vanished = open.closed(Resolution.VANISHED, "console-drift-service", null, T0.plusSeconds(10), null);
        Finding accepted = open.closed(Resolution.ACCEPTED, "dana", "on purpose", T0.plusSeconds(10), null);
        assertThat(vanished.storedAs(vanished)).isTrue();
        assertThat(vanished.storedAs(accepted)).isFalse();
        assertThat(open.changed(FIELDS, Severity.INFO, T0).storedAs(open)).isFalse();
        assertThat(open.storedAs(null)).isFalse();
    }
}
