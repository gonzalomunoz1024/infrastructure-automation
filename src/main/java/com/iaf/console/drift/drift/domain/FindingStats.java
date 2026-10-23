package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The drift numbers of the executive home (WP 9.5), the contract's {@code FindingStats}: findings
 * opened and closed in a window, the closed ones by resolution, the opened ones by severity, how
 * many are open now and the mean time a finding closed in the window stayed open. No account means
 * every account, which only the console's own services may ask for.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingStats(String accountId, Instant since, Instant until, long opened, Closed closed, long open, Map<String, Long> bySeverity,
        Double meanMinutesToClose) {

    public FindingStats {
        closed = closed == null ? new Closed(0, 0, 0, 0, 0) : closed;
        bySeverity = bySeverity == null ? Map.of() : Map.copyOf(new TreeMap<>(bySeverity));
    }

    /** The findings closed in the window, by how they closed. */
    public record Closed(long total, long reverted, long accepted, long ignored, long vanished) {

        /** These counts with one more finding closed as {@code resolution}. */
        public Closed plus(Resolution resolution) {
            return new Closed(total + 1, reverted + (resolution == Resolution.REVERTED ? 1 : 0), accepted + (resolution == Resolution.ACCEPTED ? 1 : 0),
                    ignored + (resolution == Resolution.IGNORED ? 1 : 0), vanished + (resolution == Resolution.VANISHED ? 1 : 0));
        }
    }

    /** A mean in minutes to one decimal, as the executive home shows it; null when nothing closed. */
    public static Double meanMinutes(long totalMillis, long closedCount) {
        return closedCount == 0 ? null : Math.round(totalMillis / 6000.0 / closedCount) / 10.0;
    }

    /** Counts findings one by one into the numbers of a window, for a store that holds them all at hand. */
    public static final class Tally {

        private final String accountId;
        private final Instant since;
        private final Instant until;
        private final Map<String, Long> bySeverity = new TreeMap<>();
        private long opened;
        private long open;
        private Closed closed = new Closed(0, 0, 0, 0, 0);
        private long closedMillis;

        public Tally(String accountId, Instant since, Instant until) {
            this.accountId = accountId;
            this.since = since;
            this.until = until;
        }

        public void add(Finding f) {
            if (f.status() == FindingStatus.OPEN) {
                open++;
            }
            if (within(f.openedAt())) {
                opened++;
                bySeverity.merge(f.severity().id(), 1L, Long::sum);
            }
            if (f.status() == FindingStatus.CLOSED && within(f.closedAt())) {
                closed = closed.plus(f.resolution());
                closedMillis += Duration.between(f.openedAt(), f.closedAt()).toMillis();
            }
        }

        public FindingStats stats() {
            return new FindingStats(accountId, since, until, opened, closed, open, bySeverity, meanMinutes(closedMillis, closed.total()));
        }

        private boolean within(Instant at) {
            return at != null && !at.isBefore(since) && at.isBefore(until);
        }
    }
}
