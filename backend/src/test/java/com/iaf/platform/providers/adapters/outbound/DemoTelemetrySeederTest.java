package com.iaf.platform.providers.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.adapters.outbound.DemoTelemetrySeeder.Profile;
import com.iaf.platform.providers.adapters.outbound.DemoTelemetrySeeder.Story;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderHealthRule;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.SampleContent;

import reactor.core.publisher.Flux;

/** The background traffic is read from the registry: every provider gets a judgeable day, and three of them the admin view's story. */
class DemoTelemetrySeederTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    /** Two providers no sample content has: the seeder knows them only from the registry. */
    static final ProviderDefinition LEDGER = ProviderYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: ledger, title: Ledger API, owner: team-finance, version: 2.4.1 }
            spec:
              type: http
              endpoints: { prod: https://ledger.example.com }
              actions:
                - { id: entry.get, operation: { method: GET, path: "/entries/{id}" }, effect: read, retry: safe }
                - { id: entry.post, operation: { method: POST, path: /entries }, effect: create, retry: unsafe }
              errors:
                - { match: { status: 503 }, class: unavailable, retry: true }
              slo: { successRate: 99.5, p95Ms: 600 }
            """);
    static final ProviderDefinition TICKETS = ProviderYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: tickets, title: Ticket desk, owner: team-support, version: 0.9.0 }
            spec:
              type: openapi
              spec: { url: https://tickets.example.com/openapi.json, sha256: 1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988 }
              endpoints: { prod: https://tickets.example.com }
              actions:
                - { id: ticket.get, operation: getTicket, effect: read, retry: safe }
                - { id: ticket.close, operation: closeTicket, effect: change, retry: unsafe }
              errors:
                - { match: { status: 404 }, class: not_found, retry: false }
                - { match: { status: 429 }, class: rate_limited, retry: true }
              slo: { successRate: 99.0, p95Ms: 900 }
            """);

    @Test
    void twoProvidersNoContentNamesGetSamplesAndOneOfThemIsFailing() {
        List<ProviderDefinition> two = List.of(LEDGER, TICKETS);
        Map<String, Profile> profiles = DemoTelemetrySeeder.profiles(two);

        List<Health.Status> bands = new ArrayList<>();
        for (ProviderDefinition def : two) {
            Profile profile = profiles.get(def.name());
            List<CallSample> samples = DemoTelemetrySeeder.samples(def, profile, NOW, DemoTelemetrySeeder.random(def));
            assertDesigned(def, profile, samples);
            Health health = ProviderHealthRule.judge(samples, def, NOW);
            assertThat(health.status()).as("%s (%s): %s", def.name(), profile.story(), health.reasons()).isEqualTo(band(profile.story()));
            bands.add(health.status());
        }
        assertThat(bands).containsOnlyOnce(Health.Status.FAILING);
    }

    @Test
    void everyContentProviderGetsSamplesAndEachStoryLandsInItsBand() {
        List<ProviderDefinition> content = SampleContent.providers();
        Map<String, Profile> profiles = DemoTelemetrySeeder.profiles(content);
        assertThat(profiles.keySet()).containsExactlyInAnyOrderElementsOf(content.stream().map(ProviderDefinition::name).toList());

        Map<Story, List<String>> byStory = new EnumMap<>(Story.class);
        for (ProviderDefinition def : content) {
            Profile profile = profiles.get(def.name());
            byStory.computeIfAbsent(profile.story(), k -> new ArrayList<>()).add(def.name());
            List<CallSample> samples = DemoTelemetrySeeder.samples(def, profile, NOW, DemoTelemetrySeeder.random(def));
            assertDesigned(def, profile, samples);
            Health health = ProviderHealthRule.judge(samples, def, NOW);
            assertThat(health.status()).as("%s (%s): %s", def.name(), profile.story(), health.reasons()).isEqualTo(band(profile.story()));
        }
        assertThat(byStory.get(Story.FAILING_HOUR)).hasSize(1);
        assertThat(byStory.get(Story.SLOW)).hasSize(1);
        assertThat(byStory.get(Story.FLAKY)).hasSize(1);
        assertThat(byStory.get(Story.HEALTHY)).hasSize(content.size() - 3);
    }

    @Test
    void aStoryOnlyGoesToAProviderThatHoldsItWhateverTheDraw() {
        for (ProviderDefinition def : SampleContent.providers()) {
            boolean ownSlo = def.actions().stream().anyMatch(a -> a.slo() != null);
            if (DemoTelemetrySeeder.callsPerDay(def) < DemoTelemetrySeeder.STORY_MIN_CALLS) {
                assertThat(DemoTelemetrySeeder.fits(def, Story.FAILING_HOUR)).as(def.name()).isFalse();
            }
            if (ownSlo) {
                assertThat(DemoTelemetrySeeder.fits(def, Story.SLOW)).as(def.name()).isFalse();
                assertThat(DemoTelemetrySeeder.fits(def, Story.FLAKY)).as(def.name()).isFalse();
            }
        }
        // a registry of runners alone has no story to tell: every one of them is healthy
        List<ProviderDefinition> runners = SampleContent.providers().stream()
                .filter(d -> DemoTelemetrySeeder.callsPerDay(d) < DemoTelemetrySeeder.STORY_MIN_CALLS).toList();
        assertThat(runners).isNotEmpty();
        assertThat(DemoTelemetrySeeder.profiles(runners).values()).allMatch(p -> p.story() == Story.HEALTHY);
    }

    @Test
    void seedRecordsEverySampleExactlyOnce() {
        List<ProviderDefinition> two = List.of(LEDGER, TICKETS);
        ProviderQueries providers = mock(ProviderQueries.class);
        when(providers.all()).thenAnswer(i -> Flux.fromIterable(two));
        TelemetryPort telemetry = mock(TelemetryPort.class);
        DemoTelemetrySeeder seeder = new DemoTelemetrySeeder(new PlatformProperties(), providers, telemetry, Clock.fixed(NOW, ZoneOffset.UTC));

        int recorded = seeder.seed();
        assertThat(seeder.seed()).as("an instance seeds once").isZero();
        seeder.onReady();

        Map<String, Profile> profiles = DemoTelemetrySeeder.profiles(two);
        List<CallSample> designed = new ArrayList<>();
        for (ProviderDefinition def : two) {
            designed.addAll(DemoTelemetrySeeder.samples(def, profiles.get(def.name()), NOW, DemoTelemetrySeeder.random(def)));
        }
        ArgumentCaptor<CallSample> captor = ArgumentCaptor.forClass(CallSample.class);
        verify(telemetry, times(designed.size())).record(captor.capture());
        assertThat(recorded).isEqualTo(designed.size());
        assertThat(captor.getAllValues()).containsExactlyElementsOf(designed);
    }

    @Test
    void staysQuietWhenSeedingIsOff() {
        ProviderQueries providers = mock(ProviderQueries.class);
        TelemetryPort telemetry = mock(TelemetryPort.class);
        PlatformProperties props = new PlatformProperties();
        props.getDemo().setSeedHistory(false);
        new DemoTelemetrySeeder(props, providers, telemetry, Clock.systemUTC()).onReady();
        verify(telemetry, never()).record(any());
    }

    /** The band each story is designed to show. */
    static Health.Status band(Story story) {
        return switch (story) {
            case HEALTHY -> Health.Status.HEALTHY;
            case FAILING_HOUR -> Health.Status.FAILING;
            case SLOW, FLAKY -> Health.Status.DEGRADED;
        };
    }

    /** The samples of one provider follow its profile: a fixed count a day, its actions and version, and a failing hour's burst. */
    static void assertDesigned(ProviderDefinition def, Profile profile, List<CallSample> samples) {
        int burst = DemoTelemetrySeeder.burst(profile);
        assertThat(samples).hasSize(profile.callsPerDay() * DemoTelemetrySeeder.DAYS + burst);
        assertThat(samples).isSortedAccordingTo(Comparator.comparing(CallSample::at));
        Set<String> actions = def.actions().isEmpty() ? Set.of("call") : def.actions().stream().map(Action::id).collect(Collectors.toSet());
        assertThat(samples).allMatch(s -> s.provider().equals(def.name()) && actions.contains(s.action()) && s.durationMs() > 0
                && !s.at().isAfter(NOW) && s.at().isAfter(NOW.minus(Duration.ofDays(DemoTelemetrySeeder.DAYS + 1))));
        assertThat(samples).allMatch(s -> s.ok() == (s.errorClass() == null));
        assertThat(samples).allMatch(s -> def.metadata().version().equals(s.providerVersion()));
        assertThat(samples).noneMatch(s -> "not_found".equals(s.errorClass()));
        assertThat(samples.stream().map(CallSample::action).distinct().count()).isGreaterThanOrEqualTo(Math.min(2, actions.size()));
        if (burst > 0) {
            String incident = DemoTelemetrySeeder.incidentClass(def);
            assertThat(samples.stream().filter(s -> !s.ok() && s.at().isAfter(NOW.minus(Duration.ofHours(1))) && incident.equals(s.errorClass())).count())
                    .as("%s: the failing hour", def.name())
                    .isGreaterThanOrEqualTo(burst);
        }
    }
}
