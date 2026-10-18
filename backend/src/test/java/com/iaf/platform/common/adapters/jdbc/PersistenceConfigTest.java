package com.iaf.platform.common.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.iaf.platform.config.PlatformProperties;

class PersistenceConfigTest {

    static final Instant FIRST_START = Instant.parse("2026-10-17T10:00:00Z");

    @RegisterExtension
    final TestDatabase db = TestDatabase.h2();

    static PlatformProperties withDemoHistory(boolean on) {
        PlatformProperties props = new PlatformProperties();
        props.getDemo().setSeedHistory(on);
        return props;
    }

    @Test
    void theFirstStartOnADatabaseSeedsTheDemoHistoryAndALaterStartDoesNot() {
        PlatformProperties first = withDemoHistory(true);
        PersistenceConfig.demoHistoryOnce(db.jdbc(), first, Clock.fixed(FIRST_START, ZoneOffset.UTC));
        assertThat(first.getDemo().isSeedHistory()).isTrue();
        assertThat(db.jdbc().seededAt(PersistenceConfig.DEMO_HISTORY)).contains(FIRST_START);

        PlatformProperties second = withDemoHistory(true);
        PersistenceConfig.demoHistoryOnce(new Jdbc(db.dataSource()), second, Clock.fixed(FIRST_START.plusSeconds(60), ZoneOffset.UTC));
        assertThat(second.getDemo().isSeedHistory()).isFalse();
        assertThat(db.jdbc().seededAt(PersistenceConfig.DEMO_HISTORY)).contains(FIRST_START);
    }

    @Test
    void aStartWithoutTheDemoHistoryClaimsNothing() {
        PlatformProperties off = withDemoHistory(false);
        PersistenceConfig.demoHistoryOnce(db.jdbc(), off, Clock.fixed(FIRST_START, ZoneOffset.UTC));
        assertThat(db.jdbc().seededAt(PersistenceConfig.DEMO_HISTORY)).isEmpty();

        PlatformProperties on = withDemoHistory(true);
        PersistenceConfig.demoHistoryOnce(db.jdbc(), on, Clock.fixed(FIRST_START, ZoneOffset.UTC));
        assertThat(on.getDemo().isSeedHistory()).isTrue();
    }
}
