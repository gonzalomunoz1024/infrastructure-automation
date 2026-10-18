package com.iaf.platform.assistant.application;

import java.time.Clock;
import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.config.PlatformProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps builder sessions for {@code platform.assistant.session-retention} (30 days by default), as
 * the telemetry retention keeps provider-call samples: every hour, a minute after the start, the
 * sessions not updated within it are deleted, whatever store holds them. By then a session has
 * ended: a stream lasts at most {@code devin-timeout}, so one still marked working that long ago
 * was cut off by a restart. A blank, zero or negative retention keeps the default.
 */
@Slf4j
@Component
public class SessionRetention {

    static final Duration DEFAULT = Duration.ofDays(30);

    private final SessionStore sessions;
    private final Clock clock;
    private final Duration retention;

    public SessionRetention(SessionStore sessions, Clock clock, PlatformProperties props) {
        this.sessions = sessions;
        this.clock = clock;
        Duration configured = props.getAssistant().getSessionRetention();
        this.retention = configured == null || configured.isNegative() || configured.isZero() ? DEFAULT : configured;
    }

    public Duration retention() {
        return retention;
    }

    /** Deletes the sessions older than the retention; returns how many. */
    public int purge() {
        return sessions.deleteUpdatedBefore(clock.instant().minus(retention));
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgeQuietly() {
        try {
            int deleted = purge();
            if (deleted > 0) {
                log.info("deleted {} builder session(s) not updated for {}", deleted, retention);
            }
        } catch (RuntimeException e) {
            log.warn("builder session retention failed: {}", e.toString());
        }
    }
}
