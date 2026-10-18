package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

/** A call continues the platform's trace: the traceparent reaches the handler, its logs and a span of its own. */
class CallTracingTest {

    static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    static final String STEP_SPAN = "b7ad6b7169203331";
    static final String TRACEPARENT = "00-" + TRACE_ID + "-" + STEP_SPAN + "-01";

    private final List<SpanData> ended = new CopyOnWriteArrayList<>();
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void installAnOpenTelemetrySdk() {
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
                ended.add(span.toSpanData());
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }
        }).build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return tracerProvider;
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }
        });
    }

    @AfterEach
    void removeIt() {
        GlobalOpenTelemetry.resetForTest();
        tracerProvider.close();
    }

    record Seen(JobInfo job, SpanContext span, String mdcTraceparent, String mdcTraceId) {
    }

    private static Provider probe(AtomicReference<Seen> seen, boolean fail) {
        return Provider.named("probe").owner("team-platform").version("1.4.0")
                .error("quota_exceeded", false, "No room left")
                .action(Action.json("probe.run", (ctx, in) -> {
                    seen.set(new Seen(ctx.job(), Span.current().getSpanContext(), MDC.get("traceparent"), MDC.get("traceId")));
                    if (fail) {
                        throw ctx.fatal("quota_exceeded", "No room left for team-x");
                    }
                    return in;
                }).effect(Effect.READ))
                .build();
    }

    private static ObjectNode call(String traceparent) {
        ObjectNode payload = DispatcherTest.envelope(DispatcherTest.input("a", "b"));
        if (traceparent != null) {
            ((ObjectNode) payload.get("job")).put("traceparent", traceparent);
        }
        return payload;
    }

    @Test
    void theHandlerRunsInASpanOfItsOwnThatContinuesThePlatformsStep() {
        AtomicReference<Seen> seen = new AtomicReference<>();

        new Dispatcher(probe(seen, false)).dispatch("probe.run", call(TRACEPARENT), d -> {
        });

        assertThat(ended).hasSize(1);
        SpanData span = ended.get(0);
        assertThat(span.getName()).isEqualTo("run probe.probe.run");
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(span.getParentSpanId()).isEqualTo(STEP_SPAN);
        assertThat(span.getParentSpanContext().isRemote()).isTrue();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("provider"))).isEqualTo("probe");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("provider.version"))).isEqualTo("1.4.0");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("action"))).isEqualTo("probe.run");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("job.id"))).isEqualTo("job-7");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("workflow"))).isEqualTo("add-item");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("step"))).isEqualTo("create");
        assertThat(span.getAttributes().get(AttributeKey.longKey("attempt"))).isEqualTo(2L);
        Seen s = seen.get();
        assertThat(s.job().traceparent()).isEqualTo(TRACEPARENT);
        assertThat(s.span().getSpanId()).as("the handler's current span").isEqualTo(span.getSpanId());
        assertThat(s.mdcTraceparent()).isEqualTo(TRACEPARENT);
        assertThat(s.mdcTraceId()).isEqualTo(TRACE_ID);
        assertThat(MDC.get("traceparent")).as("the MDC after the call").isNull();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void aFailedCallMarksItsSpanWithTheErrorClass() {
        AtomicReference<Seen> seen = new AtomicReference<>();

        assertThatThrownBy(() -> new Dispatcher(probe(seen, true)).dispatch("probe.run", call(TRACEPARENT), d -> {
        })).isInstanceOf(ProviderException.class);

        assertThat(ended).singleElement().satisfies(span -> {
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(span.getStatus().getDescription()).isEqualTo("quota_exceeded");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("error.class"))).isEqualTo("quota_exceeded");
            assertThat(span.getEvents()).extracting(e -> e.getName()).containsExactly("exception");
        });
        assertThat(MDC.get("traceparent")).isNull();
    }

    @Test
    void theTraceparentAtTheTopOfThePayloadIsReadToo() {
        AtomicReference<Seen> seen = new AtomicReference<>();
        ObjectNode payload = call(null);
        payload.put("traceparent", TRACEPARENT);

        new Dispatcher(probe(seen, false)).dispatch("probe.run", payload, d -> {
        });

        assertThat(seen.get().job().traceparent()).isEqualTo(TRACEPARENT);
        assertThat(ended).singleElement().satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(STEP_SPAN));
    }

    @Test
    void withoutAValidTraceparentNoSpanStartsAndTheCallRunsAsBefore() {
        AtomicReference<Seen> seen = new AtomicReference<>();
        Dispatcher d = new Dispatcher(probe(seen, false));

        d.dispatch("probe.run", call(null), x -> {
        });
        assertThat(seen.get().job().traceparent()).isNull();
        assertThat(seen.get().span().isValid()).isFalse();
        assertThat(seen.get().mdcTraceparent()).isNull();

        for (String broken : new String[] {"not-a-traceparent", "00-" + "0".repeat(32) + "-" + STEP_SPAN + "-01", "ff-" + TRACE_ID + "-" + STEP_SPAN + "-01"}) {
            d.dispatch("probe.run", call(broken), x -> {
            });
            assertThat(seen.get().job().traceparent()).as("what the platform sent").isEqualTo(broken);
            assertThat(seen.get().span().isValid()).as(broken).isFalse();
        }
        assertThat(ended).isEmpty();
    }

    @Test
    void theJobsTraceIdIsInTheLogsEvenWithoutATraceparent() {
        AtomicReference<Seen> seen = new AtomicReference<>();
        ObjectNode payload = call(null);
        ((ObjectNode) payload.get("job")).put("traceId", TRACE_ID);

        new Dispatcher(probe(seen, false)).dispatch("probe.run", payload, d -> {
        });

        assertThat(seen.get().mdcTraceId()).isEqualTo(TRACE_ID);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void aTestEnvelopeCarriesTheTraceparentWhereThePlatformPutsIt() {
        ObjectNode json = Envelope.of(null).withTraceparent(TRACEPARENT).toJson();

        assertThat(json.at("/job/traceparent").asText()).isEqualTo(TRACEPARENT);
        assertThat(Envelope.of(null).toJson().path("job").has("traceparent")).isFalse();
    }
}
