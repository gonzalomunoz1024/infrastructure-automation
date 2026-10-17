package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderHealthRule;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.SampleContent;

import reactor.core.publisher.Flux;

/** The background traffic gives every provider a judgeable day and the designed health story. */
class DemoTelemetrySeederTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void designedProfilesLandInTheirBands() {
        for (ProviderDefinition def : SampleContent.providers()) {
            DemoTelemetrySeeder.Profile profile = DemoTelemetrySeeder.PROFILES.getOrDefault(def.name(), DemoTelemetrySeeder.DEFAULT);
            List<CallSample> samples = DemoTelemetrySeeder.samples(def, profile, NOW, new Random(DemoTelemetrySeeder.SEED ^ def.name().hashCode()));

            assertThat(samples).hasSize(profile.callsPerDay() * DemoTelemetrySeeder.DAYS);
            assertThat(samples).isSortedAccordingTo(java.util.Comparator.comparing(CallSample::at));
            assertThat(samples).allMatch(s -> s.provider().equals(def.name()) && s.action() != null && s.durationMs() > 0
                    && !s.at().isAfter(NOW) && s.at().isAfter(NOW.minus(Duration.ofDays(DemoTelemetrySeeder.DAYS + 1))));
            assertThat(samples).allMatch(s -> s.ok() == (s.errorClass() == null));
            assertThat(samples.stream().map(CallSample::action).distinct().count()).isGreaterThanOrEqualTo(Math.min(2, def.actions().size()));

            List<CallSample> day = samples.stream().filter(s -> s.at().isAfter(NOW.minus(ProviderHealthRule.WINDOW))).toList();
            assertThat(day).hasSize(profile.callsPerDay());
            Health health = ProviderHealthRule.judge(day, def.spec().slo(), NOW);
            Health.Status expected = switch (def.name()) {
                case "backup", "mongodb" -> Health.Status.DEGRADED;
                default -> Health.Status.HEALTHY;
            };
            assertThat(health.status()).as("%s: %s", def.name(), health.reasons()).isEqualTo(expected);
        }
    }

    @Test
    void seedRecordsEverySampleOnceAndIsDeterministic() {
        ProviderQueries providers = mock(ProviderQueries.class);
        when(providers.all()).thenAnswer(i -> Flux.fromIterable(SampleContent.providers()));
        TelemetryPort telemetry = mock(TelemetryPort.class);
        PlatformProperties props = new PlatformProperties();
        DemoTelemetrySeeder seeder = new DemoTelemetrySeeder(props, providers, telemetry, Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        int first = seeder.seed();
        int second = seeder.seed();

        int expected = SampleContent.providers().stream()
                .mapToInt(d -> DemoTelemetrySeeder.PROFILES.getOrDefault(d.name(), DemoTelemetrySeeder.DEFAULT).callsPerDay() * DemoTelemetrySeeder.DAYS)
                .sum();
        assertThat(first).isEqualTo(expected).isEqualTo(second);
        verify(telemetry, atLeast(expected)).record(any(CallSample.class));
    }

    @Test
    void staysQuietWhenSeedingIsOff() {
        ProviderQueries providers = mock(ProviderQueries.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        PlatformProperties props = new PlatformProperties();
        props.getDemo().setSeedHistory(false);
        new DemoTelemetrySeeder(props, providers, telemetry, Clock.systemUTC()).onReady();
        verify(telemetry, org.mockito.Mockito.never()).record(any());
    }
}
