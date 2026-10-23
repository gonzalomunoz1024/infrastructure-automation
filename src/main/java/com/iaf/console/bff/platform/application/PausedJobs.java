package com.iaf.console.bff.platform.application;

import java.time.Duration;

import com.iaf.console.platform.client.PlatformException;

import reactor.util.retry.Retry;

/**
 * A job shows it is paused (its approval listed, its status {@code waiting-approval} or
 * {@code needs-attention}) a moment before it takes the decision: platform-fake makes the pause
 * visible, then parks the job, and a decision in that moment answers 409 {@code job.notPaused}.
 * The same decision a few milliseconds later is taken, so the calls that decide a paused job
 * (approve, reject, retry, skip, abort) ask again on that answer alone; the last refusal is the
 * one answered.
 */
public final class PausedJobs {

    public static final Retry NOT_YET_WAITING = Retry.backoff(4, Duration.ofMillis(20)).maxBackoff(Duration.ofMillis(200))
            .filter(PausedJobs::notYetWaiting)
            .onRetryExhaustedThrow((spec, signal) -> signal.failure());

    private PausedJobs() {
    }

    public static boolean notYetWaiting(Throwable e) {
        return e instanceof PlatformException p && p.status() == 409 && p.errorText() != null && p.errorText().startsWith("job.notPaused");
    }
}
