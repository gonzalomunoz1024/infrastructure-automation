package com.iaf.console.bff.digests.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The weekly digest (decision 32.4): when it is written ({@code cron}, in UTC: Mondays at 06:00),
 * whether this replica writes on that schedule ({@code scheduled}; off, the digests are written
 * only when asked), how often a replica looks whether a week is due (so a run missed while every
 * replica was down is caught up), and how long a replica holds an account's week while it writes.
 */
@ConfigurationProperties(prefix = "console.digests")
public record DigestsProperties(
        @DefaultValue("0 0 6 * * MON") String cron,
        @DefaultValue("true") boolean scheduled,
        @DefaultValue("10m") Duration checkEvery,
        @DefaultValue("10m") Duration lease) {
}
