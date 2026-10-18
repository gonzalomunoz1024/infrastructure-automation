package com.iaf.platform.common.tracing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.TooManyRequestsException;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * The platform's spans, through Micrometer's Observation API: the OpenTelemetry bridge turns them
 * into spans (and the OTLP exporter ships them when {@code management.otlp.tracing.endpoint} is
 * set); with tracing off every call is a no-op and trace ids come from {@link TraceIds}. A span's
 * parent is always named: the observation in the Reactor context (WebFlux puts the request's
 * there), one remembered under its trace id while it is open (so a job started inside a request
 * hangs under it), or none, with the trace id asked for (so a job's spans land in the trace its
 * {@code traceId} names, whichever thread runs them).
 */
@Component
public class Traces {

    static final int MAX_REMEMBERED = 10_000;
    /** The tag a failed span carries its error class in; {@code none} on the others, so every span of a name has the same tags. */
    public static final String ERROR_CLASS = "error.class";
    public static final String NONE = "none";

    private final ObservationRegistry registry;
    private final RequestedTraceIds ids;
    private final Map<String, Deque<Observation>> open = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Observation>> eldest) {
            return size() > MAX_REMEMBERED;
        }
    };

    @Autowired
    public Traces(ObjectProvider<ObservationRegistry> registry, ObjectProvider<RequestedTraceIds> ids) {
        this(registry.getIfAvailable(() -> ObservationRegistry.NOOP), ids.getIfAvailable());
    }

    public Traces(ObservationRegistry registry) {
        this(registry, null);
    }

    /** {@code ids}: the id generator installed on the SDK behind {@code registry}; null when there is none. */
    public Traces(ObservationRegistry registry, RequestedTraceIds ids) {
        this.registry = registry == null ? ObservationRegistry.NOOP : registry;
        this.ids = ids;
    }

    /** Tracing off: every span is a no-op. */
    public static Traces off() {
        return new Traces(ObservationRegistry.NOOP);
    }

    public ObservationRegistry registry() {
        return registry;
    }

    /** A started span under {@code parent}; null starts a new trace. Low-cardinality tags also tag the span's timer. */
    public Observation start(String name, String contextualName, Observation parent, Map<String, String> low, Map<String, String> high) {
        Observation o = Observation.createNotStarted(name, registry).parentObservation(parent);
        return tagged(o, contextualName, low, high).start();
    }

    /**
     * A started span in the trace {@code traceId}. Without {@code parentSpanId} it is the trace's
     * root (when the SDK takes its ids from {@link RequestedTraceIds}; otherwise it hangs under a
     * remote parent made up for it); with one it continues that remote span, as an incoming
     * {@code traceparent} would. Without a valid trace id it starts a new trace.
     */
    public Observation continueTrace(String name, String contextualName, String traceId, String parentSpanId, Map<String, String> low,
            Map<String, String> high) {
        if (!TraceIds.isTraceId(traceId)) {
            return start(name, contextualName, null, low, high);
        }
        if (parentSpanId == null && ids != null && ids.installed()) {
            return ids.root(traceId, () -> start(name, contextualName, null, low, high));
        }
        String parent = TraceIds.isSpanId(parentSpanId) ? parentSpanId : TraceIds.newSpanId();
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>((carrier, key) -> carrier.get(key), Kind.CONSUMER);
        context.setCarrier(Map.of(TraceIds.TRACEPARENT, TraceIds.traceparent(traceId, parent, true)));
        Observation o = Observation.createNotStarted(name, () -> context, registry).parentObservation(null);
        return tagged(o, contextualName, low, high).start();
    }

    private static Observation tagged(Observation o, String contextualName, Map<String, String> low, Map<String, String> high) {
        if (contextualName != null) {
            o.contextualName(contextualName);
        }
        if (low != null) {
            low.forEach((k, v) -> o.lowCardinalityKeyValue(k, v == null ? "none" : v));
        }
        if (high != null) {
            high.forEach((k, v) -> {
                if (v != null) {
                    o.highCardinalityKeyValue(k, v);
                }
            });
        }
        return o;
    }

    /** The trace id of the span behind {@code o}; null when tracing is off. */
    public String traceId(Observation o) {
        Span span = span(o);
        String id = span == null ? null : span.context().traceId();
        return TraceIds.isTraceId(id) ? id : null;
    }

    /** The span id behind {@code o}; null when tracing is off. */
    public String spanId(Observation o) {
        Span span = span(o);
        String id = span == null ? null : span.context().spanId();
        return TraceIds.isSpanId(id) ? id : null;
    }

    private static Span span(Observation o) {
        if (o == null || o.isNoop()) {
            return null;
        }
        TracingObservationHandler.TracingContext tracing = o.getContext().get(TracingObservationHandler.TracingContext.class);
        return tracing == null ? null : tracing.getSpan();
    }

    /**
     * Mark {@code o} failed with {@code errorClass} (and tag it {@code error.class}): the class
     * only, never a message, which may echo what a remote system answered.
     */
    public static void failed(Observation o, String errorClass) {
        String cls = errorClass == null || errorClass.isBlank() ? "error" : errorClass;
        o.lowCardinalityKeyValue(ERROR_CLASS, cls);
        o.error(new TracedError(cls));
    }

    /** A refusal the caller caused (400, 403, 404, 409, 429): an answer, not a failed span. */
    public static boolean refusal(Throwable e) {
        return e instanceof IllegalArgumentException || e instanceof NotFoundException || e instanceof ForbiddenException
                || e instanceof ConflictException || e instanceof TooManyRequestsException;
    }

    /** End {@code o}: failed with the class of {@code e} unless it is a {@link #refusal}, tagged {@code error.class=none} otherwise. */
    public static void stop(Observation o, Throwable e) {
        if (e != null && !refusal(e)) {
            failed(o, e.getClass().getSimpleName());
        } else {
            o.lowCardinalityKeyValue(ERROR_CLASS, NONE);
        }
        o.stop();
    }

    /** A failure as a span records it: its message is the error class. */
    public static final class TracedError extends RuntimeException {
        TracedError(String errorClass) {
            super(errorClass, null, false, false);
        }
    }

    /** The observation a Reactor context carries (the request's, in WebFlux); null when none. */
    public static Observation current(ContextView ctx) {
        return ctx == null ? null : ctx.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
    }

    /** {@code ctx} with {@code o} as the current observation, so what runs downstream (a WebClient call) hangs under it and propagates it. */
    public static Context with(Context ctx, Observation o) {
        return o == null || o.isNoop() ? ctx : ctx.put(ObservationThreadLocalAccessor.KEY, o);
    }

    // ---------------------------------------------------------------- open spans by trace id

    /** Remember {@code o} as the innermost open span of its trace until {@link #forget}. */
    public void remember(String traceId, Observation o) {
        if (traceId == null || o == null || o.isNoop()) {
            return;
        }
        synchronized (open) {
            open.computeIfAbsent(traceId, k -> new ArrayDeque<>()).push(o);
        }
    }

    /** The innermost span remembered under {@code traceId}; null when none is open. */
    public Observation recall(String traceId) {
        if (traceId == null) {
            return null;
        }
        synchronized (open) {
            Deque<Observation> d = open.get(traceId);
            return d == null ? null : d.peek();
        }
    }

    public void forget(String traceId, Observation o) {
        if (traceId == null || o == null) {
            return;
        }
        synchronized (open) {
            Deque<Observation> d = open.get(traceId);
            if (d != null) {
                d.remove(o);
                if (d.isEmpty()) {
                    open.remove(traceId);
                }
            }
        }
    }
}
