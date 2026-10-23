package com.iaf.console.fake.runs.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void noPinMatchesEverything() {
        assertThat(Versions.matches(null, "2.0.1")).isTrue();
        assertThat(Versions.matches("", "2.0.1")).isTrue();
        assertThat(Versions.matches("latest", "2.0.1")).isTrue();
    }

    @Test
    void exactPinsAndCaretRanges() {
        assertThat(Versions.matches("2.0.1", "2.0.1")).isTrue();
        assertThat(Versions.matches("2.0.0", "2.0.1")).isFalse();
        assertThat(Versions.matches("^2", "2.0.1")).isTrue();
        assertThat(Versions.matches("^1", "2.0.1")).isFalse();
        assertThat(Versions.matches("^1.2", "1.4.0")).isTrue();
        assertThat(Versions.matches("^1.5", "1.4.0")).isFalse();
        assertThat(Versions.matches("~1.4", "1.4.2")).isTrue();
        assertThat(Versions.matches("~1.4", "1.5.0")).isFalse();
    }
}
