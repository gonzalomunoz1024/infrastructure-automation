package com.iaf.console.identity.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.identity.principals.domain.DirectoryGroup;

/** The demo directory: each demo account's owning team holds the people the seed puts in that account's members group. */
class DemoDirectoryTest {

    @Test
    void theOwningTeamsHoldTheDemoMembers() {
        Map<String, List<DirectoryGroup.Member>> teams = DemoSeed.directory();
        assertThat(teams).containsOnlyKeys("team-compute", "team-data");
        assertThat(teams.get("team-compute")).containsExactly(new DirectoryGroup.Member("dana@example.com", "dana"),
                new DirectoryGroup.Member("mateo@example.com", "mateo"));
        assertThat(teams.get("team-data")).containsExactly(new DirectoryGroup.Member("raj@example.com", "raj"),
                new DirectoryGroup.Member("ana@example.com", "ana"));
    }
}
