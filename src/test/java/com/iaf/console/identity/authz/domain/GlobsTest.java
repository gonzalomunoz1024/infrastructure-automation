package com.iaf.console.identity.authz.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one wildcard rule, as OPA's {@code glob.match} with no delimiters reads it. */
class GlobsTest {

    @Test
    void starSpansColonsAndSlashes() {
        assertThat(Globs.matches("wfrn:wf:*:*:123456789012:*", "wfrn:wf:compute:global:123456789012:vm/vm-1")).isTrue();
        assertThat(Globs.matches("wfrn:wf:compute:*", "wfrn:wf:compute:global:123456789012:vm/vm-1")).isTrue();
        assertThat(Globs.matches("*:get", "compute:get")).isTrue();
        assertThat(Globs.matches("*:*", "iam:delete")).isTrue();
        assertThat(Globs.matches("compute:*", "iam:get")).isFalse();
    }

    @Test
    void questionMarkIsOneCharacter() {
        assertThat(Globs.matches("vm/web-?", "vm/web-3")).isTrue();
        assertThat(Globs.matches("vm/web-?", "vm/web-30")).isFalse();
        assertThat(Globs.matches("vm/web-?", "vm/web-")).isFalse();
    }

    @Test
    void literalsMustMatchExactly() {
        assertThat(Globs.matches("compute:get", "compute:get")).isTrue();
        assertThat(Globs.matches("compute:get", "compute:gets")).isFalse();
        assertThat(Globs.matches("compute:get", "ompute:get")).isFalse();
        assertThat(Globs.matches("", "")).isTrue();
        assertThat(Globs.matches("*", "")).isTrue();
        assertThat(Globs.matches("a**b", "ab")).isTrue();
        assertThat(Globs.matches("a**b", "a:/b")).isTrue();
        assertThat(Globs.matches(null, "x")).isFalse();
        assertThat(Globs.matches("x", null)).isFalse();
    }

    @Test
    void sentinelMakesARequestedPatternLiteral() {
        String listOverAll = Globs.sentinel("wfrn:wf:compute:global:123456789012:vm/*");
        assertThat(listOverAll).isEqualTo("wfrn:wf:compute:global:123456789012:vm/~");
        assertThat(Globs.matches("wfrn:wf:compute:global:123456789012:vm/*", listOverAll)).isTrue();
        assertThat(Globs.matches("wfrn:wf:compute:global:123456789012:vm/vm-1", listOverAll)).isFalse();
        assertThat(Globs.sentinel("vm/web-?")).isEqualTo("vm/web-^");
    }
}
