package com.iaf.console.kit.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

    @Test
    void parsesAW3cTraceparent() {
        TraceIds.Parent parent = TraceIds.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01").orElseThrow();
        assertThat(parent.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(parent.parentId()).isEqualTo("b7ad6b7169203331");
        assertThat(parent.flags()).isEqualTo("01");
        assertThat(parent.format()).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    @Test
    void rejectsWhatIsNotOne() {
        assertThat(TraceIds.parse(null)).isEmpty();
        assertThat(TraceIds.parse("")).isEmpty();
        assertThat(TraceIds.parse("00-00000000000000000000000000000000-b7ad6b7169203331-01")).isEmpty();
        assertThat(TraceIds.parse("00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01")).isEmpty();
        assertThat(TraceIds.parse("00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01")).isEmpty();
        assertThat(TraceIds.parse("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")).isEmpty();
    }

    @Test
    void mintsValidIds() {
        TraceIds.Parent fresh = TraceIds.newTrace();
        assertThat(TraceIds.parse(fresh.format())).contains(fresh);
        assertThat(TraceIds.isTraceId(TraceIds.newTraceId())).isTrue();
        assertThat(TraceIds.newSpanId()).matches("[0-9a-f]{16}");
        TraceIds.Parent child = fresh.child();
        assertThat(child.traceId()).isEqualTo(fresh.traceId());
        assertThat(child.parentId()).isNotEqualTo(fresh.parentId());
        assertThat(TraceIds.newTraceId()).isNotEqualTo(TraceIds.newTraceId());
    }
}
