package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The intervals of a waiting step's looks. */
class PollingTest {

    private static Duration s(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    @Test
    void aBackedOffPollDoublesFromFiveSecondsUpToFiveMinutes() {
        List<Duration> waits = new ArrayList<>();
        Duration last = null;
        for (int i = 0; i < 9; i++) {
            last = Polling.BACKOFF.next(last, false, null);
            waits.add(last);
        }
        assertThat(waits).containsExactly(s(5), s(10), s(20), s(40), s(80), s(160), s(300), s(300), s(300));
    }

    @Test
    void aChangeOfWhatThePollWatchesStartsTheBackoffOver() {
        assertThat(Polling.BACKOFF.next(s(300), true, null)).isEqualTo(s(5));
        assertThat(Polling.BACKOFF.next(s(5), false, null)).isEqualTo(s(10));
    }

    @Test
    void aBackedOffPollNeverSleepsPastTheDeadline() {
        assertThat(Polling.BACKOFF.next(s(40), false, s(25))).isEqualTo(s(25));
        assertThat(Polling.BACKOFF.next(s(40), false, Duration.ZERO)).as("no deadline left: the check after the wait ends the step").isEqualTo(s(80));
    }

    @Test
    void theFixedIntervalIsFiveSecondsWhateverHappens() {
        assertThat(Polling.FIXED.next(null, false, null)).isEqualTo(s(5));
        assertThat(Polling.FIXED.next(s(5), true, s(2))).isEqualTo(s(5));
        assertThat(WorkflowInterpreter.POLL_INTERVAL).isEqualTo(s(5));
    }
}
