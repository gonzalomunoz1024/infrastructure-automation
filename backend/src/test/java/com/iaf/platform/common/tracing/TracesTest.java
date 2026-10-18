package com.iaf.platform.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.NotFoundException;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import reactor.util.context.Context;

class TracesTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String PARENT = "00f067aa0ba902b7";

    TestTracing tracing = TestTracing.on();
    Traces traces = tracing.traces;

    @AfterEach
    void close() {
        tracing.close();
    }

    @Test
    void aSpanUnderAParentIsInItsTraceAndTheIdsAreReadBack() {
        Observation parent = traces.start("parent", null, null, Map.of(), Map.of());
        Observation child = traces.start("child", "child work", parent, Map.of("kind", "x"), Map.of("job.id", "job_1"));
        child.stop();
        parent.stop();

        SpanData p = tracing.one("parent");
        SpanData c = tracing.one("child work");
        assertThat(c.getTraceId()).isEqualTo(p.getTraceId());
        assertThat(c.getParentSpanId()).isEqualTo(p.getSpanId());
        assertThat(traces.traceId(child)).isEqualTo(c.getTraceId());
        assertThat(traces.spanId(child)).isEqualTo(c.getSpanId());
        assertThat(c.getAttributes().get(AttributeKey.stringKey("kind"))).isEqualTo("x");
        assertThat(c.getAttributes().get(AttributeKey.stringKey("job.id"))).isEqualTo("job_1");
    }

    @Test
    void aTraceIdAskedForStartsThatTraceAsItsRoot() {
        Observation o = traces.continueTrace("job", null, TRACE, null, Map.of(), Map.of());
        o.stop();

        SpanData span = tracing.one("job");
        assertThat(span.getTraceId()).isEqualTo(TRACE);
        assertThat(span.getParentSpanId()).isEqualTo(SpanId.getInvalid());
        assertThat(span.getParentSpanContext().isValid()).isFalse();
    }

    @Test
    void withoutTheIdGeneratorTheTraceContinuesUnderARemoteParent() {
        try (TestTracing plain = TestTracing.withoutRequestedIds()) {
            plain.traces.continueTrace("job", null, TRACE, null, Map.of(), Map.of()).stop();
            plain.traces.continueTrace("call", null, TRACE, PARENT, Map.of(), Map.of()).stop();

            SpanData job = plain.one("job");
            assertThat(job.getTraceId()).isEqualTo(TRACE);
            assertThat(job.getParentSpanContext().isRemote()).isTrue();
            SpanData call = plain.one("call");
            assertThat(call.getTraceId()).isEqualTo(TRACE);
            assertThat(call.getParentSpanId()).isEqualTo(PARENT);
        }
    }

    @Test
    void aRemoteParentIsContinuedAndAnInvalidTraceIdStartsANewTrace() {
        traces.continueTrace("continued", null, TRACE, PARENT, Map.of(), Map.of()).stop();
        traces.continueTrace("fresh", null, "trc_1", null, Map.of(), Map.of()).stop();

        assertThat(tracing.one("continued").getParentSpanId()).isEqualTo(PARENT);
        assertThat(tracing.one("continued").getTraceId()).isEqualTo(TRACE);
        assertThat(tracing.one("fresh").getTraceId()).matches("[0-9a-f]{32}").isNotEqualTo(TRACE);
    }

    @Test
    void theIdGeneratorHandsOutTheAskedIdOnceAndRandomIdsOtherwise() {
        RequestedTraceIds ids = new RequestedTraceIds();
        assertThat(ids.installed()).isFalse();
        assertThat(ids.root(TRACE, () -> ids.generateTraceId() + "," + ids.generateTraceId())).startsWith(TRACE + ",").doesNotEndWith(TRACE);
        assertThat(ids.generateTraceId()).isNotEqualTo(TRACE).matches("[0-9a-f]{32}");
        assertThat(ids.root("not-a-trace", ids::generateTraceId)).matches("[0-9a-f]{32}");
    }

    @Test
    void aFailureRecordsItsErrorClassAndNotItsMessageAndARefusalIsNoFailure() {
        Observation failed = traces.start("failed", null, null, Map.of(), Map.of());
        Traces.failed(failed, "timeout");
        failed.stop();
        Traces.stop(traces.start("refused", null, null, Map.of(), Map.of()), new NotFoundException("job", "job_1"));
        Traces.stop(traces.start("broken", null, null, Map.of(), Map.of()), new IllegalStateException("password=hunter2"));

        SpanData f = tracing.one("failed");
        assertThat(f.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(f.getAttributes().get(AttributeKey.stringKey(Traces.ERROR_CLASS))).isEqualTo("timeout");
        SpanData r = tracing.one("refused");
        assertThat(r.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(r.getAttributes().get(AttributeKey.stringKey(Traces.ERROR_CLASS))).isEqualTo(Traces.NONE);
        SpanData b = tracing.one("broken");
        assertThat(b.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(b.getAttributes().get(AttributeKey.stringKey(Traces.ERROR_CLASS))).isEqualTo("IllegalStateException");
        assertThat(b.toString()).doesNotContain("hunter2");
    }

    @Test
    void theInnermostOpenSpanOfATraceIsRecalledUntilItIsForgotten() {
        Observation outer = traces.start("outer", null, null, Map.of(), Map.of());
        Observation inner = traces.start("inner", null, outer, Map.of(), Map.of());
        String trace = traces.traceId(outer);
        traces.remember(trace, outer);
        traces.remember(trace, inner);

        assertThat(traces.recall(trace)).isSameAs(inner);
        traces.forget(trace, inner);
        assertThat(traces.recall(trace)).isSameAs(outer);
        traces.forget(trace, outer);
        assertThat(traces.recall(trace)).isNull();
        assertThat(traces.recall(null)).isNull();
    }

    @Test
    void theReactorContextCarriesTheCurrentObservation() {
        Observation o = traces.start("o", null, null, Map.of(), Map.of());
        assertThat(Traces.current(Traces.with(Context.empty(), o))).isSameAs(o);
        assertThat(Traces.current(Context.empty())).isNull();
        assertThat(Traces.with(Context.empty(), Observation.NOOP).isEmpty()).isTrue();
    }

    @Test
    void withTracingOffEverySpanIsANoOpWithoutIds() {
        Traces off = Traces.off();
        Observation o = off.start("x", null, null, Map.of("a", "b"), Map.of());
        assertThat(o.isNoop()).isTrue();
        assertThat(off.traceId(o)).isNull();
        assertThat(off.continueTrace("y", null, TRACE, null, Map.of(), Map.of()).isNoop()).isTrue();
        off.remember(TRACE, o);
        assertThat(off.recall(TRACE)).isNull();
    }
}
