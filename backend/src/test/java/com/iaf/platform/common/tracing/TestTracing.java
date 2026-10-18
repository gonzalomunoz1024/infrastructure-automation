package com.iaf.platform.common.tracing;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.test.web.reactive.server.MockServerConfigurer;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * What Spring Boot wires for tracing (the OpenTelemetry SDK behind Micrometer's bridge, W3C
 * propagation, the tracing observation handlers), with every ended span collected in memory.
 */
public final class TestTracing implements AutoCloseable {

    public final List<SpanData> spans = new CopyOnWriteArrayList<>();
    public final RequestedTraceIds ids = new RequestedTraceIds();
    public final ObservationRegistry registry = ObservationRegistry.create();
    public final Traces traces;
    private final SdkTracerProvider provider;

    private TestTracing(boolean requestedIds) {
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(new Collector()));
        if (requestedIds) {
            ids.customize(builder);
        }
        provider = builder.build();
        io.opentelemetry.api.trace.Tracer otel = provider.get("test");
        OtelCurrentTraceContext current = new OtelCurrentTraceContext();
        OtelTracer tracer = new OtelTracer(otel, current, event -> {
        }, new OtelBaggageManager(current, List.of(), List.of()));
        OtelPropagator propagator = new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
        registry.observationConfig().observationHandler(new FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
        traces = new Traces(registry, requestedIds ? ids : null);
    }

    /** As Spring Boot sets it up: the SDK takes its trace ids from {@link RequestedTraceIds}. */
    public static TestTracing on() {
        return new TestTracing(true);
    }

    /** An SDK with OpenTelemetry's own id generator only. */
    public static TestTracing withoutRequestedIds() {
        return new TestTracing(false);
    }

    /** Server observations for a WebTestClient, as Spring Boot configures WebFlux. */
    public MockServerConfigurer server() {
        return new MockServerConfigurer() {
            @Override
            public void beforeServerCreated(WebHttpHandlerBuilder builder) {
                builder.observationRegistry(registry);
            }
        };
    }

    public List<SpanData> named(String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).toList();
    }

    /** The one ended span named {@code name}. */
    public SpanData one(String name) {
        List<SpanData> found = named(name);
        if (found.size() != 1) {
            throw new AssertionError("expected one span named " + name + " but found " + spans.stream().map(SpanData::getName).toList());
        }
        return found.get(0);
    }

    public SpanData withId(String spanId) {
        return spans.stream().filter(s -> s.getSpanId().equals(spanId)).findFirst()
                .orElseThrow(() -> new AssertionError("no span " + spanId + " among " + spans.stream().map(SpanData::getName).toList()));
    }

    @Override
    public void close() {
        provider.close();
    }

    private final class Collector implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
