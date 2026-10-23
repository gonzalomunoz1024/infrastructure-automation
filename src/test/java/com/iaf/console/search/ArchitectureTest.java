package com.iaf.console.search;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.arch.ConsoleArchitectureRules;

/** The console's layout rules over this service: scoped reads, a pure domain, an application without adapters, adapters in their slice. */
class ArchitectureTest {

    @Test
    void theServiceKeepsTheConsolesLayout() {
        ConsoleArchitectureRules.checkAll("com.iaf.console.search");
    }
}
