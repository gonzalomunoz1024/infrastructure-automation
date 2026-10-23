package com.iaf.console.identity;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.arch.ConsoleArchitectureRules;

/** The console's layout rules over this service's production classes. */
class ArchitectureTest {

    @Test
    void holdsTheConsoleArchitectureRules() {
        ConsoleArchitectureRules.checkAll("com.iaf.console.identity");
    }
}
