package com.iaf.console.bff.platform.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformOperation;
import com.iaf.console.platform.model.PlatformError;

import reactor.core.publisher.Mono;

/** A decision sent the moment a job shows it is paused is asked again; any other refusal is answered at once. */
class PausedJobsTest {

    private static PlatformException refused(int status, String error) {
        return new PlatformException(PlatformOperation.RETRY_JOB, status, new PlatformError(error, "corr-1", null, null, null, null), null, "corr-1");
    }

    @Test
    void onlyTheNotYetPausedConflictIsAskedAgain() {
        assertTrue(PausedJobs.notYetWaiting(refused(409, "job.notPaused: nothing is waiting for a decision")));
        assertFalse(PausedJobs.notYetWaiting(refused(409, "approval.notPending: the approval is approved")));
        assertFalse(PausedJobs.notYetWaiting(refused(400, "job.notPaused")));
        assertFalse(PausedJobs.notYetWaiting(new IllegalStateException("job.notPaused")));
    }

    @Test
    void aDecisionThatMetTheMomentBeforeThePauseIsTakenOnTheNextAsk() {
        AtomicInteger calls = new AtomicInteger();
        String answer = Mono.defer(() -> calls.incrementAndGet() < 3 ? Mono.error(refused(409, "job.notPaused: nothing is waiting for a decision"))
                : Mono.just("taken")).retryWhen(PausedJobs.NOT_YET_WAITING).block();
        assertEquals("taken", answer);
        assertEquals(3, calls.get());
    }

    @Test
    void anotherRefusalIsAnsweredAtOnceAndTheLastNotYetPausedOneWhenItLasts() {
        AtomicInteger calls = new AtomicInteger();
        PlatformException notPending = refused(409, "approval.notPending: the approval is approved");
        PlatformException thrown = assertThrows(PlatformException.class,
                () -> Mono.defer(() -> { calls.incrementAndGet(); return Mono.error(notPending); }).retryWhen(PausedJobs.NOT_YET_WAITING).block());
        assertSame(notPending, thrown);
        assertEquals(1, calls.get());

        PlatformException stuck = refused(409, "job.notPaused: the job is running");
        PlatformException last = assertThrows(PlatformException.class,
                () -> Mono.error(stuck).retryWhen(PausedJobs.NOT_YET_WAITING).block());
        assertSame(stuck, last);
    }
}
