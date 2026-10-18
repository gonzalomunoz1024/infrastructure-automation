package com.iaf.platform.gateway.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * {@code platform.gateway.*}: the guards on job starts. Idempotency keys answer for {@code ttl};
 * quotas are token buckets per team, {@code per-minute} sustained starts with room for
 * {@code burst} at once, with per-team overrides under {@code teams}.
 */
@Data
@Configuration
@ConfigurationProperties("platform.gateway")
public class GatewayProperties {

    private Idempotency idempotency = new Idempotency();
    private Quotas quotas = new Quotas();

    @Data
    public static class Idempotency {
        /** How long a key returns its first answer. */
        private Duration ttl = Duration.ofHours(24);
        /** Keys kept at most; the oldest go first. */
        private int keep = 100_000;
    }

    @Data
    public static class Quotas {
        private boolean enabled = true;
        /** Every team's sustained job starts per minute, unless {@code teams} says otherwise. */
        private int perMinute = 60;
        /** Every team's starts allowed at once. */
        private int burst = 60;
        /** Overrides by team: {@code { team-data: { per-minute: 120, burst: 120 } }}. */
        private Map<String, Limit> teams = new LinkedHashMap<>();
    }

    @Data
    public static class Limit {
        private Integer perMinute;
        private Integer burst;
    }
}
