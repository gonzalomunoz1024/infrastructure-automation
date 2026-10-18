package com.iaf.platform.providers.ports.outbound;

import java.util.concurrent.atomic.AtomicInteger;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Marks a provider call made for review (the wizard's Try it and the verify call after it) in the
 * Reactor context. Such a call reaches the real system, but it is not a run's call: a
 * {@code ProviderCallPort} records no telemetry sample for it, so it never counts towards the
 * health or the alerts of a published provider of the same name.
 * Usage: {@code providerCalls.call(call).contextWrite(ReviewCall::with)}; an adapter reads it with
 * {@code Mono.deferContextual(ctx -> ... ReviewCall.in(ctx) ...)}. A review that shows the answer's
 * HTTP status passes a holder ({@link #with(Context, AtomicInteger)}), which an adapter that speaks
 * HTTP fills ({@link #answered}); the others leave it as it was.
 */
public final class ReviewCall {

    private static final String KEY = "platform.provider-review-call";
    private static final String STATUS = "platform.provider-review-status";

    private ReviewCall() {
    }

    /** The context with the review mark. */
    public static Context with(Context context) {
        return context.put(KEY, Boolean.TRUE);
    }

    /** The context with the review mark and the holder of the answer's HTTP status. */
    public static Context with(Context context, AtomicInteger status) {
        return with(context).put(STATUS, status);
    }

    /** Reports the HTTP status a review call was answered with, where the review asked for it. */
    public static void answered(ContextView context, int status) {
        context.<AtomicInteger>getOrEmpty(STATUS).ifPresent(h -> h.set(status));
    }

    /** Whether the call being made is a review call. */
    public static boolean in(ContextView context) {
        return context.getOrDefault(KEY, Boolean.FALSE);
    }
}
