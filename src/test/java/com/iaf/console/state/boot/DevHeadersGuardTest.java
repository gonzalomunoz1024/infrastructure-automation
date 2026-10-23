package com.iaf.console.state.boot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The dev headers name any caller, so they start only in the demo profile and never beside a real database or Kafka. */
class DevHeadersGuardTest {

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void onlyTheDemoProfileTakesTheDevHeaders() {
        assertThatCode(() -> AuthConfiguration.requireDemo(profiles("demo"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> AuthConfiguration.requireDemo(profiles())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthConfiguration.requireDemo(profiles("mongo"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthConfiguration.requireDemo(profiles("demo", "mongo"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthConfiguration.requireDemo(profiles("demo", "kafka"))).isInstanceOf(IllegalStateException.class);
    }
}
