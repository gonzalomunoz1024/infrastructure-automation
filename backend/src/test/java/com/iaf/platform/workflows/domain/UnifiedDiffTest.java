package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

    @Test
    void equalTextsHaveNoDiff() {
        assertThat(UnifiedDiff.of("a\nb\n", "a\nb\n", "f.yaml")).isEmpty();
        assertThat(UnifiedDiff.stats("").unchanged()).isTrue();
    }

    @Test
    void aChangedLineShowsAsRemovedAndAdded() {
        String before = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n";
        String after = "one\ntwo\nthree\nfour\nFIVE\nsix\nseven\neight\n";
        String diff = UnifiedDiff.of(before, after, "f.yaml");
        assertThat(diff).startsWith("--- a/f.yaml\n+++ b/f.yaml\n@@ -2,7 +2,7 @@\n");
        assertThat(diff).contains("-five\n+FIVE\n");
        assertThat(diff).doesNotContain(" one\n");
        assertThat(UnifiedDiff.stats(diff)).isEqualTo(new UnifiedDiff.Stats(1, 1));
    }

    @Test
    void aNewFileIsAllAdditions() {
        String diff = UnifiedDiff.of("", "a\nb\n", "f.yaml");
        assertThat(diff).isEqualTo("--- /dev/null\n+++ b/f.yaml\n@@ -0,0 +1,2 @@\n+a\n+b\n");
        assertThat(UnifiedDiff.stats(diff)).isEqualTo(new UnifiedDiff.Stats(2, 0));
    }

    @Test
    void farApartChangesMakeSeparateHunks() {
        StringBuilder before = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            before.append("line ").append(i).append('\n');
        }
        String after = before.toString().replace("line 3\n", "line three\n").replace("line 28\n", "line twenty-eight\n");
        String diff = UnifiedDiff.of(before.toString(), after, "f.yaml");
        assertThat(diff.split("\n")).filteredOn(l -> l.startsWith("@@")).hasSize(2);
        assertThat(diff).contains("@@ -1,6 +1,6 @@").contains("@@ -25,6 +25,6 @@");
    }

    @Test
    void insertionsAndDeletionsKeepTheLineNumbersRight() {
        String diff = UnifiedDiff.of("a\nb\nc\n", "a\nx\nb\n", "f.yaml");
        assertThat(diff).isEqualTo("--- a/f.yaml\n+++ b/f.yaml\n@@ -1,3 +1,3 @@\n a\n+x\n b\n-c\n");
    }
}
