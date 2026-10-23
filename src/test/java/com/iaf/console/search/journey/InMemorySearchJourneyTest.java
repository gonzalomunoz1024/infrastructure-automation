package com.iaf.console.search.journey;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.iaf.console.search.support.StubStateServer;

/** The journey on the in-memory twin, which the demo runs by default. */
class InMemorySearchJourneyTest extends SearchJourneyTest {

    static final StubStateServer STATE = seeded();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        common(registry, STATE);
        registry.add("console.search.engine", () -> "memory");
    }

    @Override
    protected StubStateServer state() {
        return STATE;
    }

    @AfterAll
    static void stop() {
        STATE.close();
    }
}
