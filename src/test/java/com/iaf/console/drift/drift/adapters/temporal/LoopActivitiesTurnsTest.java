package com.iaf.console.drift.drift.adapters.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

/** The turns other parts of the service hand to the Temporal loops: run by kind, each answering its summary. */
class LoopActivitiesTurnsTest {

    @Test
    void aHandedInTurnRunsByItsKind() {
        Map<String, Supplier<Mono<String>>> turns = Map.of(LoopSpec.ALARMS, () -> Mono.just("3 evaluated"), LoopSpec.UTILISATION, Mono::empty);
        LoopActivitiesImpl activities = new LoopActivitiesImpl(null, null, null, turns);
        assertThat(activities.tick(new LoopSpec(LoopSpec.ALARMS, "global", 0, null, 60_000, 100))).isEqualTo("3 evaluated");
        assertThat(activities.tick(new LoopSpec(LoopSpec.UTILISATION, "global", 0, null, 3_600_000, 100))).isEqualTo("nothing");
        assertThatThrownBy(() -> activities.tick(new LoopSpec("weather", "global", 0, null, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoopActivitiesImpl(null, null, null).tick(new LoopSpec(LoopSpec.ALARMS, "global", 0, null, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachLoopIsOneWorkflowPerRegion() {
        assertThat(new LoopSpec(LoopSpec.ALARMS, "eu-1", 0, null, 60_000, 100).workflowId()).isEqualTo("console-drift-eu-1-alarms");
        assertThat(new LoopSpec(LoopSpec.UTILISATION, "eu-1", 0, null, 3_600_000, 100).workflowId()).isEqualTo("console-drift-eu-1-utilisation");
    }
}
