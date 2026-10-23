package com.iaf.console.search.index.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.iaf.console.search.TestClock;

/** The token bucket: a burst, then the sustained rate, per account. */
class QueryBudgetTest {

    @Test
    void aBurstThenTheRate() {
        TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
        QueryBudget budget = new QueryBudget(2, 3, clock);
        assertThat(budget.take("a")).isZero();
        assertThat(budget.take("a")).isZero();
        assertThat(budget.take("a")).isZero();
        assertThat(budget.take("a")).as("the fourth at once waits").isEqualTo(1);
        assertThat(budget.take("b")).as("another account has its own budget").isZero();
        clock.advance(Duration.ofMillis(500));
        assertThat(budget.take("a")).as("half a second refills one token at two per second").isZero();
        assertThat(budget.take("a")).isEqualTo(1);
        clock.advance(Duration.ofHours(2));
        budget.forgetIdle(3600);
        assertThat(budget.size()).isZero();
        assertThat(budget.take("a")).isZero();
    }
}
