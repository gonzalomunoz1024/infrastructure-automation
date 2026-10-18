package com.iaf.platform.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    @Test
    void startsFullAndRefusesOnceEmptySayingHowLongToWait() {
        TokenBucket b = new TokenBucket(60, 3, T0);
        assertThat(b.take(T0)).isEmpty();
        assertThat(b.take(T0)).isEmpty();
        assertThat(b.take(T0)).isEmpty();
        assertThat(b.take(T0)).hasValue(Duration.ofSeconds(1));
    }

    @Test
    void refillsAtItsRateUpToItsCapacity() {
        TokenBucket b = new TokenBucket(30, 2, T0);
        b.take(T0);
        b.take(T0);
        assertThat(b.take(T0.plusSeconds(1))).hasValueSatisfying(wait -> assertThat(wait).isEqualTo(Duration.ofSeconds(1)));
        assertThat(b.take(T0.plusSeconds(2))).isEmpty();
        assertThat(b.available(T0.plus(Duration.ofHours(1)))).isEqualTo(2d);
    }

    @Test
    void aClockGoingBackwardsChangesNothing() {
        TokenBucket b = new TokenBucket(60, 1, T0);
        b.take(T0);
        assertThat(b.take(T0.minusSeconds(30))).isPresent();
        assertThat(b.available(T0)).isZero();
    }

    @Test
    void needsAPositiveRateAndBurst() {
        assertThatThrownBy(() -> new TokenBucket(0, 1, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucket(1, 0, T0)).isInstanceOf(IllegalArgumentException.class);
    }
}
