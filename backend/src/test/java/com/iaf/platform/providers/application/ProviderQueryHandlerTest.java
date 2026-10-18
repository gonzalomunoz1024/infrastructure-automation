package com.iaf.platform.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.domain.ActionInfo;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;
import com.iaf.platform.providers.domain.ProviderDetail.PerAction;
import com.iaf.platform.providers.domain.ProviderSummary;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries.Use;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.releases.adapters.outbound.InMemoryReleaseStore;
import com.iaf.platform.releases.application.ReleaseQueryHandler;
import com.iaf.platform.releases.domain.Release;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProviderQueryHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: fleet, title: Fleet API, owner: team-compute, version: 1.1.0, description: The fleet }
            spec:
              type: openapi
              spec: { url: https://fleet.example.com/openapi.json, sha256: 9f2c1b7e0a4d5c6b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d }
              endpoints: { dev: https://fleet-dev.example.com, prod: https://fleet.example.com }
              auth: { type: bearer, credential: vault:fleet/token }
              actions:
                - id: vm.get
                  title: Get VM
                  operation: getVm
                  effect: read
                  retry: safe
                  output: { type: object, properties: { id: { type: string } } }
                - id: vm.delete
                  operation: deleteVm
                  effect: delete
                  retry: unsafe
                  approval: required
                  slo: { successRate: 99.0, p95Ms: 1500 }
              overrides:
                vm.get: { response: { properties: { powerState: { type: string } } } }
              slo: { successRate: 99.5, p95Ms: 800 }
              alerts: { channel: "#compute-alerts" }
            """;

    ProviderStorePort store;
    TelemetryPort telemetry;
    ChangeRequestPort changeRequests;
    ProviderQueryHandler handler;
    StoredProvider fleet;
    InMemoryReleaseStore releases;

    @BeforeEach
    void setUp() {
        store = mock(ProviderStorePort.class);
        telemetry = mock(TelemetryPort.class);
        changeRequests = mock(ChangeRequestPort.class);
        fleet = new StoredProvider(ProviderYaml.parse(YAML), YAML, "providers/team-compute/fleet.yaml", NOW, "content");
        when(store.all()).thenReturn(List.of(fleet));
        when(store.get("fleet")).thenReturn(Optional.of(fleet));
        when(store.get("nope")).thenReturn(Optional.empty());
        when(changeRequests.all()).thenReturn(List.of());
        ProviderAlertEvaluator alerts = new ProviderAlertEvaluator(store, telemetry, new EventBus(CLOCK), CLOCK);
        releases = new InMemoryReleaseStore();
        handler = new ProviderQueryHandler(store, telemetry, changeRequests, alerts, CLOCK, new ReleaseQueryHandler(releases));
    }

    static CallSample sample(Instant at, String action, boolean ok, long ms, String errorClass) {
        return new CallSample(at, "fleet", action, "dev", ok, ms, errorClass, ok ? null : "job_7", "vm-restart", "1.2.0");
    }

    @Test
    void theDefinitionIsServedByNameOrNot() {
        StepVerifier.create(handler.provider("fleet")).assertNext(d -> assertThat(d.name()).isEqualTo("fleet")).verifyComplete();
        StepVerifier.create(handler.provider("nope")).verifyComplete();
        StepVerifier.create(handler.all()).expectNextCount(1).verifyComplete();
    }

    String versionFor(Use use) {
        return handler.provider("fleet").contextWrite(c -> Use.with(c, use)).map(d -> d.metadata().version()).block();
    }

    @Test
    void aJobGetsTheVersionTheProviderReleaseRoutesItToAndEveryOtherReadTheCurrentOne() {
        String v2 = YAML.replace("version: 1.1.0", "version: 1.2.0");
        when(store.version("fleet", "1.2.0")).thenReturn(Optional.of(new StoredProvider(ProviderYaml.parse(v2), v2, fleet.path(), NOW, "mateo")));
        when(store.version("fleet", "1.1.0")).thenReturn(Optional.of(fleet));
        Release canary = releases.save(Release.canary("rel_1", null, "1.1.0", "1.2.0", List.of(10, 50, 100), NOW, "mateo").forProvider("fleet"));
        String inShare = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "job_" + i)
                .filter(id -> ReleaseQueryHandler.candidateJob("rel_1", id, 10)).findFirst().orElseThrow();
        String outOfShare = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "job_" + i)
                .filter(id -> !ReleaseQueryHandler.candidateJob("rel_1", id, 10)).findFirst().orElseThrow();

        assertThat(handler.provider("fleet").block().metadata().version()).as("no job: the registry and the checks").isEqualTo("1.1.0");
        assertThat(versionFor(new Use("job_x", "dev", false))).as("sandbox workflows get the candidate at once").isEqualTo("1.2.0");
        assertThat(versionFor(new Use(outOfShare, "prod", true))).as("so do dry runs").isEqualTo("1.2.0");
        assertThat(versionFor(new Use(inShare, "prod", false))).isEqualTo("1.2.0");
        assertThat(versionFor(new Use(outOfShare, "prod", false))).isEqualTo("1.1.0");
        assertThat(versionFor(new Use(null, "prod", false))).as("a call outside a job").isEqualTo("1.1.0");

        releases.save(canary.paused(NOW, "mateo"));
        assertThat(versionFor(new Use(inShare, "prod", false))).as("a paused release sends every job back").isEqualTo("1.1.0");
        releases.save(canary.rolledBack(NOW, "rolled back", "mateo"));
        assertThat(versionFor(new Use("job_x", "dev", false))).as("an ended release routes nothing").isEqualTo("1.1.0");
    }

    @Test
    void theListJudgesHealthFromTheLastDayOfTelemetry() {
        when(telemetry.samples(isNull(), any())).thenReturn(List.of());
        StepVerifier.create(handler.list())
                .assertNext(s -> {
                    assertThat(s.name()).isEqualTo("fleet");
                    assertThat(s.title()).isEqualTo("Fleet API");
                    assertThat(s.environments()).containsExactly("dev", "prod");
                    assertThat(s.actionCount()).isEqualTo(2);
                    assertThat(s.health().status()).isEqualTo(Health.Status.UNKNOWN);
                    assertThat(s.slo().p95Ms()).isEqualTo(800);
                    assertThat(s.alerts().channel()).isEqualTo("#compute-alerts");
                })
                .verifyComplete();
        List<CallSample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(sample(NOW.minusSeconds(60L * i), "vm.get", true, 100, null));
        }
        when(telemetry.samples(isNull(), eq(NOW.minus(Duration.ofDays(14))))).thenReturn(samples);
        StepVerifier.create(handler.list())
                .assertNext(s -> {
                    assertThat(s.health().status()).isEqualTo(Health.Status.HEALTHY);
                    assertThat(s.health().calls()).isEqualTo(10);
                })
                .verifyComplete();
    }

    @Test
    void theDetailHasTheFileTheActionsTwoWeeksOfTelemetryAndTheChangeRequests() {
        List<CallSample> samples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            samples.add(sample(NOW.minusSeconds(60L * (i + 1)), "vm.get", true, 100, null));
        }
        samples.add(sample(NOW.minusSeconds(10), "vm.get", false, 2000, "unavailable"));
        samples.add(sample(NOW.minus(Duration.ofDays(3)), "vm.delete", true, 300, null));
        samples.add(sample(NOW.minus(Duration.ofDays(3)).plusSeconds(5), "vm.delete", false, 400, "conflict"));
        when(telemetry.samples(eq("fleet"), eq(NOW.minus(Duration.ofDays(14))))).thenReturn(samples);
        ChangeRequest mine = new ChangeRequest("cr_1", ChangeRequest.Kind.PROVIDER, "fleet", "1.1.0", "change/provider-fleet-1.1.0", "t", "mateo",
                ChangeRequest.Status.OPEN, List.of(), "all", YAML, "", NOW.minusSeconds(100), null, null, null);
        ChangeRequest older = new ChangeRequest("cr_0", ChangeRequest.Kind.PROVIDER, "fleet", "1.0.0", "b", "t", "mateo",
                ChangeRequest.Status.MERGED, List.of(), "all", YAML, "", NOW.minusSeconds(5000), NOW, "gonzalo", null);
        ChangeRequest workflow = new ChangeRequest("cr_2", ChangeRequest.Kind.WORKFLOW, "fleet", "1.0.0", "b", "t", "mateo",
                ChangeRequest.Status.OPEN, List.of(), "all", "", "", NOW, null, null, null);
        ChangeRequest other = new ChangeRequest("cr_3", ChangeRequest.Kind.PROVIDER, "other", "1.0.0", "b", "t", "mateo",
                ChangeRequest.Status.OPEN, List.of(), "all", "", "", NOW, null, null, null);
        when(changeRequests.all()).thenReturn(List.of(older, workflow, mine, other));

        StepVerifier.create(handler.detail("fleet"))
                .assertNext(d -> {
                    assertThat(d.yaml()).isEqualTo(YAML);
                    assertThat(d.description()).isEqualTo("The fleet");
                    assertThat(d.actions()).extracting(ActionInfo::id).containsExactly("vm.get", "vm.delete");
                    ActionInfo get = d.actions().get(0);
                    assertThat(get.overridden()).isTrue();
                    assertThat(get.output().path("properties").path("powerState").path("type").asText()).isEqualTo("string");
                    assertThat(get.output().path("properties").path("id").path("type").asText()).isEqualTo("string");
                    assertThat(get.retry()).isEqualTo("safe");
                    assertThat(d.actions().get(1).overridden()).isFalse();
                    assertThat(d.actions().get(1).approval()).isEqualTo("required");
                    assertThat(d.actions().get(1).title()).isEqualTo("vm.delete");
                    assertThat(d.health().status()).isEqualTo(Health.Status.FAILING);
                    assertThat(d.health().calls()).isEqualTo(6);
                    assertThat(d.endpoints()).containsEntry("dev", "https://fleet-dev.example.com").containsEntry("prod", "https://fleet.example.com");
                    assertThat(d.auth().type()).isEqualTo("bearer");
                    assertThat(d.auth().credential()).isEqualTo("vault:fleet/token");
                    // 24-hour windows ending now: the newest holds the same six calls as the health judgement
                    assertThat(d.daily()).hasSize(14);
                    assertThat(d.daily().get(13).date()).isEqualTo("2026-10-16T10:00:00Z");
                    assertThat(d.daily().get(13).calls()).isEqualTo(6);
                    assertThat(d.daily().get(13).errors()).isEqualTo(1);
                    assertThat(d.daily().get(13).successRate()).isEqualTo(83.3);
                    assertThat(d.daily().get(11).date()).isEqualTo("2026-10-14T10:00:00Z");
                    assertThat(d.daily().get(11).calls()).isEqualTo(2);
                    assertThat(d.daily().get(11).errors()).isEqualTo(1);
                    assertThat(d.daily().get(0).calls()).isZero();
                    assertThat(d.daily().get(0).errors()).isZero();
                    assertThat(d.daily().get(0).successRate()).isNull();
                    assertThat(d.daily().get(0).p95Ms()).isNull();
                    // the summary's error trend is the same fortnight: one failed call per window that had one
                    assertThat(d.health().errorTrend()).hasSize(14);
                    assertThat(d.health().errorTrend().get(13)).isEqualTo(1);
                    assertThat(d.health().errorTrend().get(11)).isEqualTo(1);
                    assertThat(d.health().errorTrend().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
                    assertThat(d.perAction()).extracting(PerAction::action).containsExactly("vm.delete", "vm.get");
                    PerAction get24 = d.perAction().get(1);
                    assertThat(get24.calls()).isEqualTo(6);
                    assertThat(get24.p95Ms()).isEqualTo(2000);
                    assertThat(get24.topErrors()).hasSize(1);
                    assertThat(get24.topErrors().get(0).errorClass()).isEqualTo("unavailable");
                    assertThat(get24.topErrors().get(0).count()).isEqualTo(1);
                    assertThat(get24.slo()).as("vm.get takes the provider's SLO").isEqualTo(new Slo(99.5, 800));
                    assertThat(get24.band()).isEqualTo(Health.Status.FAILING);
                    PerAction delete = d.perAction().get(0);
                    assertThat(delete.slo()).as("vm.delete has its own").isEqualTo(new Slo(99.0, 1500));
                    assertThat(delete.band()).as("its calls are three days old").isEqualTo(Health.Status.UNKNOWN);
                    assertThat(d.actions().get(0).slo()).isNull();
                    assertThat(d.actions().get(1).slo()).isEqualTo(new Slo(99.0, 1500));
                    assertThat(d.changeRequests()).extracting(ChangeRequest::id).containsExactly("cr_1", "cr_0");
                })
                .verifyComplete();
    }

    @Test
    void unknownProvidersAre404() {
        StepVerifier.create(handler.detail("nope")).expectError(NotFoundException.class).verify();
        StepVerifier.create(handler.alerts("nope")).expectError(NotFoundException.class).verify();
        StepVerifier.create(handler.alerts("fleet")).verifyComplete();
    }

    static final Identity ANA = new Identity("ana", "Ana", null, List.of("team-data"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity ADMIN = new Identity("gonzalo", "Gonzalo", null, List.of("team-platform"), List.of("admin"), true, Identity.Kind.USER);

    static <T> Mono<T> as(Mono<T> mono, Identity who) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    static <T> Flux<T> as(Flux<T> flux, Identity who) {
        return flux.contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    @Test
    void theAdminViewShowsTheCallerTheirTeamsProvidersAndTheUnownedOnesAndHidesTheRestAsUnknown() {
        String unowned = YAML.replace("name: fleet, title: Fleet API, owner: team-compute,", "name: shared, title: Shared API,");
        StoredProvider shared = new StoredProvider(ProviderYaml.parse(unowned), unowned, "providers/shared.yaml", NOW, "content");
        when(store.all()).thenReturn(List.of(fleet, shared));
        when(store.get("shared")).thenReturn(Optional.of(shared));
        when(telemetry.samples(any(), any())).thenReturn(List.of());

        StepVerifier.create(as(handler.list(), ANA).map(ProviderSummary::name)).expectNext("shared").verifyComplete();
        StepVerifier.create(as(handler.list(), MATEO).map(ProviderSummary::name)).expectNext("fleet", "shared").verifyComplete();
        StepVerifier.create(as(handler.list(), ADMIN).map(ProviderSummary::name)).expectNext("fleet", "shared").verifyComplete();
        StepVerifier.create(handler.list().map(ProviderSummary::name)).as("the platform itself is an admin").expectNext("fleet", "shared").verifyComplete();

        StepVerifier.create(as(handler.detail("fleet"), ANA))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(NotFoundException.class).hasMessage("Provider fleet not found"))
                .verify();
        StepVerifier.create(as(handler.alerts("fleet"), ANA)).expectError(NotFoundException.class).verify();
        StepVerifier.create(as(handler.detail("shared"), ANA)).assertNext(d -> assertThat(d.owner()).isNull()).verifyComplete();
        StepVerifier.create(as(handler.detail("fleet"), MATEO)).assertNext(d -> assertThat(d.name()).isEqualTo("fleet")).verifyComplete();
        StepVerifier.create(as(handler.alerts("fleet"), MATEO)).verifyComplete();
        StepVerifier.create(as(handler.detail("fleet"), ADMIN)).expectNextCount(1).verifyComplete();

        // the definitions for the checks, the interpreter and the registry are not scoped
        StepVerifier.create(as(handler.provider("fleet"), ANA)).expectNextCount(1).verifyComplete();
        StepVerifier.create(as(handler.all(), ANA)).expectNextCount(2).verifyComplete();
    }
}
