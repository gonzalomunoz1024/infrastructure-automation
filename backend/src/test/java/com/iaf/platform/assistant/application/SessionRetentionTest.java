package com.iaf.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.assistant.adapters.outbound.InMemorySessionStore;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.config.PlatformProperties;

class SessionRetentionTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static AssistantSession updated(String id, Instant at) {
        return new AssistantSession(id, AssistantSession.CREATE, null, "ana", AssistantSession.DONE, null, List.of(), "devin-1", at, at);
    }

    @Test
    void sessionsNotUpdatedWithinTheRetentionAreDeleted() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(updated("asst_old", NOW.minus(Duration.ofDays(31))));
        store.save(updated("asst_recent", NOW.minus(Duration.ofDays(29))));
        PlatformProperties props = new PlatformProperties();

        SessionRetention retention = new SessionRetention(store, CLOCK, props);

        assertThat(retention.retention()).isEqualTo(Duration.ofDays(30));
        assertThat(retention.purge()).isEqualTo(1);
        assertThat(store.get("asst_old")).isEmpty();
        assertThat(store.get("asst_recent")).isPresent();
    }

    @Test
    void theRetentionIsConfigurableAndANonsenseOneKeepsTheDefault() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(updated("asst_week", NOW.minus(Duration.ofDays(8))));
        PlatformProperties props = new PlatformProperties();
        props.getAssistant().setSessionRetention(Duration.ofDays(7));

        new SessionRetention(store, CLOCK, props).purgeQuietly();
        assertThat(store.get("asst_week")).isEmpty();

        props.getAssistant().setSessionRetention(Duration.ZERO);
        assertThat(new SessionRetention(store, CLOCK, props).retention()).isEqualTo(SessionRetention.DEFAULT);
        props.getAssistant().setSessionRetention(Duration.ofDays(-1));
        assertThat(new SessionRetention(store, CLOCK, props).retention()).isEqualTo(SessionRetention.DEFAULT);
        props.getAssistant().setSessionRetention(null);
        assertThat(new SessionRetention(store, CLOCK, props).retention()).isEqualTo(SessionRetention.DEFAULT);
    }
}
