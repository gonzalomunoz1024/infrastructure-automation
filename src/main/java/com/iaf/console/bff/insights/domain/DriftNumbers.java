package com.iaf.console.bff.insights.domain;

/**
 * The drift service's numbers for one account and window ({@code GET /v1/findings/stats}):
 * findings opened in the window, closed in it (reverted, accepted, ignored or vanished), open now,
 * and the mean minutes to close over those closed, absent when none closed. Accounts add up, the
 * mean weighted by the findings each closed; a sum is partial when the service did not answer for
 * one of its accounts.
 */
public record DriftNumbers(long found, long fixed, long open, Double meanMinutesToClose, boolean partial) {

    public static final DriftNumbers NONE = new DriftNumbers(0, 0, 0, null);

    /** An account the drift service did not answer for: it adds nothing and makes the sum partial. */
    public static final DriftNumbers UNANSWERED = new DriftNumbers(0, 0, 0, null, true);

    public DriftNumbers(long found, long fixed, long open, Double meanMinutesToClose) {
        this(found, fixed, open, meanMinutesToClose, false);
    }

    public DriftNumbers plus(DriftNumbers other) {
        Double mean;
        if (meanMinutesToClose == null || fixed == 0) {
            mean = other.meanMinutesToClose == null || other.fixed == 0 ? null : other.meanMinutesToClose;
        } else if (other.meanMinutesToClose == null || other.fixed == 0) {
            mean = meanMinutesToClose;
        } else {
            mean = (meanMinutesToClose * fixed + other.meanMinutesToClose * other.fixed) / (fixed + other.fixed);
        }
        return new DriftNumbers(found + other.found, fixed + other.fixed, open + other.open, mean, partial || other.partial);
    }

    public Insights.Drift view() {
        return new Insights.Drift(found, fixed, open, meanMinutesToClose == null ? null : Math.round(meanMinutesToClose * 60_000d),
                partial ? Boolean.TRUE : null);
    }
}
