package com.iaf.console.drift.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.iaf.console.drift.drift.adapters.temporal.LoopSpec;
import com.iaf.console.drift.monitoring.application.AlarmEvaluator;
import com.iaf.console.drift.monitoring.application.AlarmsReport;
import com.iaf.console.drift.rightsizing.application.UtilisationPass;
import com.iaf.console.drift.rightsizing.application.UtilisationReport;

import reactor.core.publisher.Mono;

/** The monitoring settings' defaults, and the summaries the Temporal loops record for each turn. */
class MonitoringSettingsTest {

    @Test
    void everySettingHasADefault() {
        MonitoringProperties p = new MonitoringProperties(null, null, null);
        assertThat(p.metrics().mode()).isEqualTo("platform");
        assertThat(p.metrics().demoMode()).isFalse();
        assertThat(p.metrics().maxRange()).isEqualTo(Duration.ofDays(92));
        assertThat(p.metrics().maxSteps()).isEqualTo(5000);
        assertThat(p.alarms().enabled()).isTrue();
        assertThat(p.alarms().interval()).isEqualTo(Duration.ofMinutes(1));
        MonitoringProperties.RightsizingSettings r = p.rightsizing();
        assertThat(r.interval()).isEqualTo(Duration.ofHours(24));
        assertThat(r.window()).isEqualTo(Duration.ofDays(14));
        assertThat(r.step()).isEqualTo(Duration.ofHours(1));
        assertThat(r.minSamples()).as("a week at an hour a point").isEqualTo(168);
        assertThat(r.idleCpu()).isEqualTo(5.0);
        assertThat(r.targetP95()).isEqualTo(60.0);
        assertThat(r.cpuField()).isEqualTo("cpu");
        assertThat(r.memoryField()).isEqualTo("memoryGb");
        // a step below a minute is no step
        MonitoringProperties.RightsizingSettings fine = new MonitoringProperties.RightsizingSettings(false, null, null, null, null, Duration.ofSeconds(5),
                Duration.ofHours(2), null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(fine.step()).isEqualTo(Duration.ofHours(1));
        assertThat(fine.minSamples()).isEqualTo(2);
        assertThat(fine.enabled()).isFalse();
        assertThat(new MonitoringProperties.MetricsSettings("demo", null, null, null).demoMode()).isTrue();
    }

    @Test
    void eachTemporalTurnRecordsItsSummary() {
        AlarmEvaluator alarms = mock(AlarmEvaluator.class);
        UtilisationPass utilisation = mock(UtilisationPass.class);
        when(alarms.evaluateAll()).thenReturn(Mono.just(new AlarmsReport(5, 1, 2, 1, 0)));
        when(utilisation.run(any(), anyBoolean())).thenReturn(Mono.just(new UtilisationReport(9, 3, 2, 4, 1, 0)));
        Map<String, Supplier<Mono<String>>> turns = TemporalConfiguration.monitoringTurns(alarms, utilisation);
        assertThat(turns.get(LoopSpec.ALARMS).get().block()).isEqualTo("5 evaluated, 1 fired, 2 cleared, 1 without points, 0 errors");
        assertThat(turns.get(LoopSpec.UTILISATION).get().block()).isEqualTo("9 read, 3 skipped, 2 opened, 4 updated, 1 superseded, 0 errors");
    }
}
