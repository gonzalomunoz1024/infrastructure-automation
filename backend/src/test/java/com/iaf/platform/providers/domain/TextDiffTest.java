package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextDiffTest {

    @Test
    void aNewFileIsAllAdditions() {
        String diff = TextDiff.unified(null, "a\nb\n", "providers/team/x.yaml");
        assertThat(diff).startsWith("--- /dev/null\n+++ b/providers/team/x.yaml\n");
        assertThat(diff).contains("+a\n+b\n");
    }

    @Test
    void changedLinesAreMarkedAndContextKept() {
        String diff = TextDiff.unified("a\nb\nc\n", "a\nB\nc\n", "x.yaml");
        assertThat(diff).contains(" a\n-b\n+B\n c\n");
        assertThat(diff).startsWith("--- a/x.yaml\n+++ b/x.yaml\n@@");
    }

    @Test
    void identicalTextsHaveOnlyAHeader() {
        assertThat(TextDiff.unified("a\n", "a\n", "x.yaml")).isEqualTo("--- a/x.yaml\n+++ b/x.yaml\n");
    }
}
