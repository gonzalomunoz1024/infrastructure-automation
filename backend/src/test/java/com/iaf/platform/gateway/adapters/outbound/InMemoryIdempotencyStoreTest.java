package com.iaf.platform.gateway.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    @Override
    IdempotencyStore newStore() {
        return new InMemoryIdempotencyStore(100);
    }

    @Test
    void expiredKeysAreDroppedAsNewOnesComeIn() {
        InMemoryIdempotencyStore memory = (InMemoryIdempotencyStore) store;
        memory.claim(request("ana", "k1", "fp1", T0), T0);
        memory.complete("ana", "k1", OK);
        Instant later = T0.plus(DAY).plusSeconds(1);
        memory.claim(request("ana", "k2", "fp2", later), later);
        assertThat(memory.size()).isEqualTo(1);
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfKeysDroppingTheOldest() {
        InMemoryIdempotencyStore small = new InMemoryIdempotencyStore(2);
        small.claim(request("ana", "k1", "fp", T0), T0);
        small.claim(request("ana", "k2", "fp", T0), T0);
        small.claim(request("ana", "k3", "fp", T0), T0);
        assertThat(small.size()).isEqualTo(2);
        assertThat(small.claim(request("ana", "k1", "fp", T0), T0)).isEmpty();
        assertThat(small.claim(request("ana", "k3", "fp", T0), T0)).isPresent();
    }
}
