package com.iaf.platform.audit.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * {@code platform.siem.*}: where evidence entries go besides the evidence store (profile
 * {@code siem}). The queue is bounded: when the SIEM is down long enough to fill it, the newest
 * entries are dropped and counted, never the platform's own work. Secrets are references only.
 */
@Data
@Configuration
@ConfigurationProperties("platform.siem")
public class SiemProperties {

    /** Entries waiting to be sent; beyond this they are dropped (counted in {@code audit.siem.dropped}). */
    private int queueCapacity = 10_000;
    private int batchSize = 100;
    /** Tries per batch and per SIEM before the batch is given up. */
    private int maxAttempts = 5;
    /** Wait before the second try; doubled on every further one. */
    private Duration initialBackoff = Duration.ofSeconds(1);

    private Hec hec = new Hec();
    private Syslog syslog = new Syslog();

    /** Splunk HTTP Event Collector, or anything that speaks it. */
    @Data
    public static class Hec {
        /** The full endpoint, like https://splunk.example.com:8088/services/collector/event; blank = off. */
        private String url = "";
        /** A reference to the HEC token, like vault:siem/hec-token; sent as {@code Authorization: Splunk <token>}. */
        private String token = "";
        private String source = "iaf-platform";
        private String sourcetype = "iaf:audit";
        /** The Splunk index; blank = the token's default. */
        private String index = "";
        private Duration timeout = Duration.ofSeconds(10);
    }

    /** RFC 5424 syslog over UDP, or TCP with octet-counting framing (RFC 6587). */
    @Data
    public static class Syslog {
        /** blank = off */
        private String host = "";
        private int port = 514;
        /** udp or tcp */
        private String protocol = "udp";
        private String appName = "iaf-platform";
        /** 13 = log audit */
        private int facility = 13;
        /** The structured-data id; 32473 is the documentation enterprise number until the firm's is set. */
        private String sdId = "iaf@32473";
        private Duration timeout = Duration.ofSeconds(5);
    }
}
