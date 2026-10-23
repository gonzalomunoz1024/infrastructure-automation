package com.iaf.console.wfrn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WfrnTest {

    private static final String VM = "wfrn:wf:compute:global:123456789012:vm/vm-01jbx7q2m4k9d3c8f5r6t1w0ya";

    @Test
    void toStringIsTheCanonicalName() {
        Wfrn w = Wfrn.parse(VM);
        assertThat(w.toString()).isEqualTo(VM);
        assertThat(w.in("123456789012", "global")).isTrue();
        assertThat(w.in("210987654321", "global")).isFalse();
        assertThat(w.withId("vm-01jbx7q2m4k9d3c8f5r6t1w0yb").id()).isEqualTo("vm-01jbx7q2m4k9d3c8f5r6t1w0yb");
    }

    @Test
    void nullPartsAreEmptySegmentsWhereTheSpecificationAllowsThem() {
        Wfrn org = new Wfrn("wf", "organizations", null, null, "account", "123456789012");
        assertThat(org.format()).isEqualTo("wfrn:wf:organizations:::account/123456789012");
        assertThatThrownBy(() -> new Wfrn("wf", null, "global", "123456789012", "vm", "x"))
                .isInstanceOf(WfrnException.class)
                .hasMessageStartingWith(WfrnException.SERVICE);
    }

    @Test
    void aPartWithAColonOrASlashCannotBuildAName() {
        assertThatThrownBy(() -> new Wfrn("wf", "a:b", "global", "123456789012", "vm", "x"))
                .isInstanceOf(WfrnException.class)
                .extracting(e -> ((WfrnException) e).code()).isEqualTo(WfrnException.SERVICE);
        assertThatThrownBy(() -> new Wfrn("wf", "compute", "global", "123456789012", "vm/x", "y"))
                .isInstanceOf(WfrnException.class)
                .extracting(e -> ((WfrnException) e).code()).isEqualTo(WfrnException.TYPE);
    }

    @Test
    void matchesNeverThrowsForAnInvalidName() {
        assertThat(Wfrn.matches("wfrn:wf:*", "arn:aws:ec2:::instance/i-1")).isFalse();
        assertThat(Wfrn.matches("wfrn:wf:*", null)).isFalse();
        assertThat(Wfrn.isValid(null)).isFalse();
        assertThatThrownBy(() -> Wfrn.parse(null)).isInstanceOf(WfrnException.class);
    }

    @Test
    void aPatternMatchesAParsedName() {
        WfrnPattern p = WfrnPattern.parse("wfrn:wf:compute:*:123456789012:vm/*");
        assertThat(p.matches(Wfrn.parse(VM))).isTrue();
        assertThat(p.text()).isEqualTo("wfrn:wf:compute:*:123456789012:vm/*");
        assertThat(p).isEqualTo(WfrnPattern.parse("wfrn:wf:compute:*:123456789012:vm/*")).hasToString(p.text());
    }

    @Test
    void mintedIdsReadTheirTypeBack() {
        assertThat(MintedIds.isMinted("resource-group-01jbx7q2m4k9d3c8f5r6t1w0yg")).isTrue();
        assertThat(MintedIds.parse("resource-group-01jbx7q2m4k9d3c8f5r6t1w0yg").type()).isEqualTo("resource-group");
        assertThat(MintedIds.isMinted("vm-1042")).isFalse();
        assertThatThrownBy(() -> MintedIds.mint("VM", "01jbx7q2m4k9d3c8f5r6t1w0ya")).isInstanceOf(WfrnException.class);
        assertThatThrownBy(() -> MintedIds.mint("vm", null)).isInstanceOf(WfrnException.class);
    }
}
