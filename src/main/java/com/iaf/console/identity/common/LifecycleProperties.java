package com.iaf.console.identity.common;

import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The account lifecycle's settings, under {@code console.identity.lifecycle}. */
@ConfigurationProperties(prefix = "console.identity.lifecycle")
public record LifecycleProperties(GroupSync groupSync) {

    public LifecycleProperties {
        groupSync = groupSync == null ? new GroupSync(null, null, null) : groupSync;
    }

    /**
     * The nightly identity provider group sync: on unless {@code enabled} is false, when the Spring
     * cron expression {@code cron} fires in {@code zone}; 02:00 UTC every day by default.
     */
    public record GroupSync(Boolean enabled, String cron, ZoneId zone) {

        public static final String DEFAULT_CRON = "0 0 2 * * *";

        public GroupSync {
            enabled = enabled == null || enabled;
            cron = cron == null || cron.isBlank() ? DEFAULT_CRON : cron.trim();
            zone = zone == null ? ZoneOffset.UTC : zone;
        }
    }
}
