package com.iaf.console.bff.insights.application;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.iaf.console.bff.insights.domain.Baseline;

/**
 * The executive home (WP 9.5). {@code baseline} is today's tickets, from a one-off export of the
 * ticket queue: {@code source} names the export, {@code lead-time} is a ticket's median lead time
 * and {@code by-workflow} the median per workflow where the export tells kinds of request apart;
 * without either, the lead time has no baseline and no hours saved are shown. The numbers of one
 * set of accounts are kept for {@code cache-ttl}; at most {@code max-jobs-per-account} requests
 * of an account are read per period, and {@code concurrency} calls run at once.
 */
@ConfigurationProperties(prefix = "console.insights")
public record InsightsProperties(Ticket baseline, @DefaultValue("5m") Duration cacheTtl, @DefaultValue("10000") int maxJobsPerAccount,
        @DefaultValue("8") int concurrency) {

    public InsightsProperties {
        baseline = baseline == null ? new Ticket(null, null, Map.of()) : baseline;
        if (maxJobsPerAccount < 1 || concurrency < 1) {
            throw new IllegalArgumentException("console.insights.max-jobs-per-account and concurrency are at least 1");
        }
    }

    /** The ticket baseline as configured. */
    public record Ticket(String source, Duration leadTime, Map<String, Duration> byWorkflow) {

        public Ticket {
            byWorkflow = byWorkflow == null ? Map.of() : Map.copyOf(byWorkflow);
        }
    }

    public Baseline tickets() {
        return new Baseline(baseline.source(), baseline.leadTime(), baseline.byWorkflow());
    }
}
