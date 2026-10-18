package com.iaf.platform.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.iaf.platform.gateway.adapters.outbound.InMemoryIdempotencyStore;
import com.iaf.platform.gateway.application.IdempotencyHandler.Proceed;
import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IdempotencyHandlerTest extends IdempotencyHandlerContract {

    @Override
    IdempotencyStore newStore() {
        return new InMemoryIdempotencyStore(100);
    }

    /** A store that says on which thread it was released. */
    static final class ReleaseWatchingStore implements IdempotencyStore {
        final IdempotencyStore inner = new InMemoryIdempotencyStore(100);
        final CountDownLatch claimed = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicReference<String> releasedOn = new AtomicReference<>();

        @Override
        public Optional<KeyedRequest> claim(KeyedRequest inFlight, Instant now) {
            Optional<KeyedRequest> held = inner.claim(inFlight, now);
            claimed.countDown();
            return held;
        }

        @Override
        public void complete(String caller, String key, StoredAnswer answer) {
            inner.complete(caller, key, answer);
        }

        @Override
        public void release(String caller, String key) {
            releasedOn.set(Thread.currentThread().getName());
            inner.release(caller, key);
            released.countDown();
        }
    }

    @Test
    void aCancelledCallFreesItsKeyOffTheCancellingThread() throws InterruptedException {
        ReleaseWatchingStore store = new ReleaseWatchingStore();
        IdempotencyHandler cancellable = new IdempotencyHandler(store, Duration.ofHours(24), CLOCK);

        Disposable call = cancellable.once("ana", "k1", "fp", Mono::never).subscribe();
        assertThat(store.claimed.await(5, TimeUnit.SECONDS)).isTrue();
        call.dispose();

        assertThat(store.released.await(5, TimeUnit.SECONDS)).as("the key is freed").isTrue();
        assertThat(store.releasedOn.get()).as("on a thread that may block, not the cancelling one").startsWith("boundedElastic");
        StepVerifier.create(cancellable.claim("ana", "k1", "fp")).assertNext(c -> assertThat(c).isInstanceOf(Proceed.class)).verifyComplete();
    }
}
