package com.iaf.platform.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String SPAN = "00f067aa0ba902b7";

    @Test
    void freshIdsAreW3cTraceAndSpanIds() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String trace = TraceIds.newTraceId();
            assertThat(trace).matches("[0-9a-f]{32}");
            assertThat(TraceIds.isTraceId(trace)).isTrue();
            assertThat(TraceIds.isSpanId(TraceIds.newSpanId())).isTrue();
            seen.add(trace);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void aTraceparentIsTakenApartAndForgivesCaseAndBlanks() {
        assertThat(TraceIds.parse("00-" + TRACE + "-" + SPAN + "-01")).contains(new TraceIds.Parent(TRACE, SPAN, true));
        assertThat(TraceIds.parse("  00-" + TRACE.toUpperCase() + "-" + SPAN + "-00 ")).contains(new TraceIds.Parent(TRACE, SPAN, false));
        assertThat(TraceIds.parse("01-" + TRACE + "-" + SPAN + "-01-future")).contains(new TraceIds.Parent(TRACE, SPAN, true));
    }

    @Test
    void whatIsNotATraceparentIsIgnored() {
        assertThat(TraceIds.parse(null)).isEmpty();
        assertThat(TraceIds.parse("")).isEmpty();
        assertThat(TraceIds.parse("trace-1")).isEmpty();
        assertThat(TraceIds.parse("ff-" + TRACE + "-" + SPAN + "-01")).isEmpty();
        assertThat(TraceIds.parse("00-" + "0".repeat(32) + "-" + SPAN + "-01")).isEmpty();
        assertThat(TraceIds.parse("00-" + TRACE + "-" + "0".repeat(16) + "-01")).isEmpty();
        assertThat(TraceIds.parse("00-" + TRACE + "-" + SPAN + "-01-extra")).isEmpty();
        assertThat(TraceIds.parse("00-" + TRACE.substring(1) + "-" + SPAN + "-01")).isEmpty();
    }

    @Test
    void aTraceparentIsWrittenFromValidIdsOnly() {
        assertThat(TraceIds.traceparent(TRACE, SPAN, true)).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        assertThat(TraceIds.traceparent(TRACE, SPAN, false)).endsWith("-00");
        assertThatThrownBy(() -> TraceIds.traceparent("trc_1", SPAN, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TraceIds.traceparent(TRACE, null, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
