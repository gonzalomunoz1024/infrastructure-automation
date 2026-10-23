package com.iaf.console.drift.scorecards.domain;

import java.util.Map;

import com.iaf.console.drift.drift.domain.Severity;

/**
 * The scoring rule (hub decision 26): {@code 100 × Σ weight × passed ÷ Σ weight × (passed + failed)},
 * rounded half up, with the weights critical 10, warning 3 and info 1. Rules that apply to nothing
 * do not count, and a score with nothing to count is null.
 */
public final class Scores {

    private Scores() {
    }

    public static int weight(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 10;
            case WARNING -> 3;
            case INFO -> 1;
        };
    }

    /** The score of rules with these counts and severities; null when nothing counts. */
    public static Integer score(Map<Rule, Counts> counts) {
        long earned = 0;
        long possible = 0;
        for (Map.Entry<Rule, Counts> e : counts.entrySet()) {
            int w = weight(e.getKey().severity());
            earned += w * e.getValue().passed();
            possible += w * e.getValue().total();
        }
        return of(earned, possible);
    }

    /** One rule's score: the share of its evaluations that pass. */
    public static Integer score(Counts counts) {
        return of(counts.passed(), counts.total());
    }

    private static Integer of(long earned, long possible) {
        if (possible == 0) {
            return null;
        }
        return (int) ((earned * 200 + possible) / (possible * 2));
    }
}
