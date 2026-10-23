package com.iaf.console.kit.correlation;

import java.util.Optional;

import org.springframework.web.server.ServerWebExchange;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * The correlation id of a request: the {@code X-Correlation-Id} header when the caller sent one,
 * else the trace id of its {@code traceparent}, else a fresh trace id. It travels in the Reactor
 * context (and from there into the MDC) and goes back on the response.
 */
public final class Correlation {

    /** The header a caller sets, and every response carries. */
    public static final String HEADER = "X-Correlation-Id";
    /** The Reactor context key and the MDC key. */
    public static final String KEY = "correlationId";
    /** The Reactor context key of the request's traceparent (as received, or minted). */
    public static final String TRACEPARENT_KEY = "traceparent";
    /** The exchange attribute the filter stores the id under. */
    public static final String ATTRIBUTE = Correlation.class.getName() + ".id";

    private Correlation() {
    }

    /** The id in a Reactor context, when the filter put one there. */
    public static Optional<String> id(ContextView ctx) {
        return ctx == null ? Optional.empty() : ctx.getOrEmpty(KEY);
    }

    /** The traceparent in a Reactor context, when the filter put one there. */
    public static Optional<String> traceparent(ContextView ctx) {
        return ctx == null ? Optional.empty() : ctx.getOrEmpty(TRACEPARENT_KEY);
    }

    /** The id of an exchange: the attribute the filter set, else derived from its headers as the filter would. */
    public static String of(ServerWebExchange exchange) {
        if (exchange == null) {
            return TraceIds.newTraceId();
        }
        Object attribute = exchange.getAttribute(ATTRIBUTE);
        if (attribute instanceof String s) {
            return s;
        }
        return derive(exchange.getRequest().getHeaders().getFirst(HEADER), exchange.getRequest().getHeaders().getFirst(TraceIds.TRACEPARENT));
    }

    /** The id for a request with these headers: the header, else the trace id, else new. */
    public static String derive(String correlationHeader, String traceparent) {
        if (correlationHeader != null && !correlationHeader.isBlank() && correlationHeader.length() <= 128) {
            return correlationHeader.trim();
        }
        return TraceIds.parse(traceparent).map(TraceIds.Parent::traceId).orElseGet(TraceIds::newTraceId);
    }

    /** A context with the id and the traceparent, for code that starts a chain outside a request (a consumer, a scheduler). */
    public static Context context(String correlationId, String traceparent) {
        Context c = Context.of(KEY, correlationId);
        return traceparent == null ? c : c.put(TRACEPARENT_KEY, traceparent);
    }
}
