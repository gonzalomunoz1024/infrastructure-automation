package com.iaf.console.fake;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.arch.ConsoleArchitectureRules;

/** The fake keeps the console's layout: pure domains, applications without adapters, adapters within their slice. */
class PlatformFakeArchitectureTest {

    @Test
    void theFakeFollowsTheConsoleRules() {
        ConsoleArchitectureRules.checkAll("com.iaf.console.fake");
    }
}
