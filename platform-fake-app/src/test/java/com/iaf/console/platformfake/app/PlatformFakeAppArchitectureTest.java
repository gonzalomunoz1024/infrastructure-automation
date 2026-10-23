package com.iaf.console.platformfake.app;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.arch.ConsoleArchitectureRules;

/** The application module keeps the console's layout, little as there is of it. */
class PlatformFakeAppArchitectureTest {

    @Test
    void theApplicationFollowsTheConsoleRules() {
        ConsoleArchitectureRules.checkAll("com.iaf.console.platformfake");
    }
}
