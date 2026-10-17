package com.iaf.platform.runs.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

class WaveSchedulerTest {

    @Test
    void wavesArePercentagesOfTheRows() {
        List<Wave> waves = WaveScheduler.plan(12, new RolloutPolicy(List.of(5, 25, 100), 20, 5));
        assertThat(waves).extracting(Wave::size).containsExactly(1, 2, 9);
        assertThat(waves).extracting(Wave::percent).containsExactly(5, 25, 100);
        assertThat(WaveScheduler.rowsOf(waves)).containsExactly(List.of(0), List.of(1, 2), List.of(3, 4, 5, 6, 7, 8, 9, 10, 11));
    }

    @Test
    void smallBatchesDropEmptyWavesAndAlwaysReachEveryRow() {
        assertThat(WaveScheduler.plan(1, RolloutPolicy.DEFAULT)).extracting(Wave::size).containsExactly(1);
        assertThat(WaveScheduler.plan(3, new RolloutPolicy(List.of(1, 5, 25), 2, 1))).extracting(Wave::size).containsExactly(1, 2);
        assertThat(WaveScheduler.plan(0, RolloutPolicy.DEFAULT)).isEmpty();
        assertThat(WaveScheduler.plan(200, new RolloutPolicy(List.of(5, 25, 100), 5, 10))).extracting(Wave::size).containsExactly(10, 40, 150);
        assertThat(WaveScheduler.plan(7, new RolloutPolicy(List.of(50), 5, 10))).extracting(Wave::size).containsExactly(7);
    }

    @Test
    void stopsWhenTheFailedShareExceedsThePolicy() {
        assertThat(WaveScheduler.shouldStop(1, 1, 5)).isTrue();
        assertThat(WaveScheduler.shouldStop(0, 3, 5)).isFalse();
        assertThat(WaveScheduler.shouldStop(1, 20, 5)).isFalse();
        assertThat(WaveScheduler.shouldStop(2, 20, 5)).isTrue();
        assertThat(WaveScheduler.shouldStop(1, 100, 0)).isTrue();
        assertThat(WaveScheduler.shouldStop(0, 0, 0)).isFalse();
    }

    @Test
    void maxParallelFallsBackToTheDefault() {
        assertThat(WaveScheduler.maxParallel(null)).isEqualTo(10);
        assertThat(WaveScheduler.maxParallel(new RolloutPolicy(List.of(100), 0, 5))).isEqualTo(10);
        assertThat(WaveScheduler.maxParallel(new RolloutPolicy(List.of(100), 3, 5))).isEqualTo(3);
    }
}
