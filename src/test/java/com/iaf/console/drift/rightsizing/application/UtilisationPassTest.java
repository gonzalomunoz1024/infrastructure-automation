package com.iaf.console.drift.rightsizing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Money;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.CostEstimatesPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.monitoring.domain.MetricPoint;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.drift.rightsizing.adapters.memory.InMemoryRecommendationStore;
import com.iaf.console.drift.rightsizing.adapters.memory.InMemoryUtilisationStore;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationAction;
import com.iaf.console.drift.rightsizing.domain.RecommendationKind;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.domain.ResourceUtilisation;
import com.iaf.console.drift.rightsizing.domain.RightsizingRules;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The utilisation pass over a small inventory: what it reads and skips, the recommendations it opens
 * and prices, what a later pass does when the size changes, the resource stops qualifying or leaves,
 * how a dismissal holds, and how a spent budget or a failed read counts.
 */
class UtilisationPassTest {

    static final String ACCOUNT = "123456789012";
    static final Instant T0 = Instant.parse("2026-10-23T12:30:00Z");
    static final TypeInfo VM = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing", false,
            false, Set.of("cpu", "memoryGb"), List.of("cpu", "memoryGb"), new TypeInfo.Metrics("vm.metrics", List.of("cpuPercent", "memoryPercent")),
            Map.of("cpu", List.of(2.0, 4.0, 8.0), "memoryGb", List.of(8.0, 16.0, 32.0)));
    static final TypeInfo VOLUME = new TypeInfo("volume", "volumes", "storage", "vsphere", "volume.get", Map.of(), Set.of(), Duration.ofMinutes(15),
            "missing", false, false, Set.of(), List.of());

    /** Made-up series: a flat level per resource, an hour a point, and failures a test plans. */
    static final class FakeMetrics implements MetricsPort {
        final Map<String, double[]> levels = new ConcurrentHashMap<>();
        final Map<String, MetricsWindow> windows = new ConcurrentHashMap<>();
        final Map<String, Deque<RuntimeException>> failures = new ConcurrentHashMap<>();
        final List<String> reads = new ArrayList<>();

        void level(String wfrn, double cpu, double memory) {
            levels.put(wfrn, new double[] {cpu, memory});
        }

        void fail(String wfrn, RuntimeException... errors) {
            failures.put(wfrn, new ArrayDeque<>(List.of(errors)));
        }

        @Override
        public boolean supports(TypeInfo type) {
            return type.hasMetrics();
        }

        @Override
        public List<String> seriesOf(TypeInfo type) {
            return type.metrics().series();
        }

        @Override
        public Mono<ResourceMetrics> getMetrics(AccountScope scope, MetricsTarget target, TypeInfo type, MetricsWindow window) {
            return Mono.defer(() -> {
                synchronized (reads) {
                    reads.add(target.wfrn());
                }
                windows.put(target.wfrn(), window);
                Deque<RuntimeException> planned = failures.get(target.wfrn());
                if (planned != null && !planned.isEmpty()) {
                    return Mono.error(planned.poll());
                }
                double[] level = levels.getOrDefault(target.wfrn(), new double[] {50, 50});
                List<MetricPoint> cpu = new ArrayList<>();
                List<MetricPoint> memory = new ArrayList<>();
                long step = window.step().toMillis();
                for (long t = Math.floorDiv(window.from().toEpochMilli() + step - 1, step) * step; t <= window.to().toEpochMilli(); t += step) {
                    cpu.add(new MetricPoint(Instant.ofEpochMilli(t), level[0]));
                    memory.add(new MetricPoint(Instant.ofEpochMilli(t), level[1]));
                }
                return Mono.just(new ResourceMetrics(target.wfrn(), window.from(), window.to(), window.stepText(),
                        List.of(new MetricSeries("cpuPercent", "percent", cpu), new MetricSeries("memoryPercent", "percent", memory)), "vsphere",
                        "vm.metrics"));
            });
        }
    }

    /** 20 a month plus 10 a CPU and 2.5 a GB, in USD. */
    static final CostEstimatesPort PRICES = (type, region, attributes) -> !attributes.path("cpu").isNumber() ? Mono.empty()
            : Mono.just(new Money(BigDecimal.valueOf(20 + 10 * attributes.path("cpu").asDouble() + 2.5 * attributes.path("memoryGb").asDouble()), "USD",
                    "list"));

    private final TestClock clock = new TestClock(T0);
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final InMemoryUtilisationStore utilisation = new InMemoryUtilisationStore();
    private final InMemoryRecommendationStore recommendations = new InMemoryRecommendationStore();
    private final FakeMetrics metrics = new FakeMetrics();
    private UtilisationPass pass;

    static String wfrn(String id) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":vm/" + id;
    }

    static Watch vm(String id, int cpu, int memoryGb, String state) {
        return Watch.builder(wfrn(id), ACCOUNT, "global", "vm").name(id).registryId(id).providerId("p-" + id).state(state)
                .desired(1, Json.object().put("cpu", cpu).put("memoryGb", memoryGb).put("name", id)).build();
    }

    @BeforeEach
    void inventory() {
        ResourceTypesPort types = new ResourceTypesPort() {
            @Override
            public Mono<TypeInfo> find(String name) {
                return Mono.justOrEmpty("vm".equals(name) ? VM : "volume".equals(name) ? VOLUME : null);
            }

            @Override
            public Flux<TypeInfo> list() {
                return Flux.just(VM, VOLUME);
            }
        };
        PlatformPort platform = mock(PlatformPort.class);
        when(platform.listCatalog(anyString())).thenReturn(Mono.just(List.of()));
        pass = new UtilisationPass(watches, types, platform, metrics, utilisation, recommendations, PRICES,
                new RightsizingRules(new RightsizingRules.Thresholds(5, 10, 20, 30, 60, 168)),
                new UtilisationPass.Settings(Duration.ofHours(24), Duration.ofHours(1), Duration.ofDays(14), Duration.ofHours(1), "cpuPercent",
                        "memoryPercent", "cpu", "memoryGb", 1, 2, 2),
                clock);
        watches.save(vm("idle", 2, 8, "active")).block();
        watches.save(vm("big", 4, 16, "active")).block();
        watches.save(vm("busy", 4, 16, "active")).block();
        watches.save(vm("moving", 4, 16, "updating")).block();
        watches.save(Watch.builder("wfrn:wf:storage:global:" + ACCOUNT + ":volume/vol-1", ACCOUNT, "global", "volume").name("vol-1").build()).block();
        metrics.level(wfrn("idle"), 2, 6);
        metrics.level(wfrn("big"), 12, 20);
        metrics.level(wfrn("busy"), 70, 80);
    }

    private Recommendation current(String id) {
        return recommendations.findCurrentByWfrn(wfrn(id)).block();
    }

    @Test
    void aPassOpensPricedRecommendationsAndSkipsWhatItCannotJudge() {
        UtilisationReport report = pass.run(null, false).block();
        assertThat(report).isEqualTo(new UtilisationReport(3, 2, 2, 0, 0, 0));
        assertThat(metrics.reads).containsExactlyInAnyOrder(wfrn("idle"), wfrn("big"), wfrn("busy"));

        Recommendation idle = current("idle");
        assertThat(idle.kind()).isEqualTo(RecommendationKind.IDLE);
        assertThat(idle.action()).isEqualTo(RecommendationAction.DELETE);
        assertThat(idle.name()).isEqualTo("idle");
        assertThat(idle.recommendationId()).startsWith("rec-");
        assertThat(idle.monthlyCost().amount()).isEqualByComparingTo("60");
        assertThat(idle.monthlySaving().amount()).isEqualByComparingTo("60");

        Recommendation big = current("big");
        assertThat(big.kind()).isEqualTo(RecommendationKind.OVERSIZED);
        assertThat(big.proposed()).isEqualTo(Json.object().put("cpu", 2L).put("memoryGb", 8L));
        assertThat(big.current()).isEqualTo(Json.object().put("cpu", 4).put("memoryGb", 16));
        assertThat(big.monthlyCost().amount()).isEqualByComparingTo("100");
        assertThat(big.monthlySaving().amount()).isEqualByComparingTo("40");
        assertThat(big.utilisation().cpuP95()).isEqualTo(12.0);
        assertThat(current("busy")).isNull();

        // the read: 14 days at an hour a point, up to the start of this hour
        MetricsWindow window = metrics.windows.get(wfrn("big"));
        assertThat(window.to()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-09T12:00:00Z"));
        assertThat(window.step()).isEqualTo(Duration.ofHours(1));
        ResourceUtilisation kept = utilisation.find(wfrn("big")).block();
        assertThat(kept.readAt()).isEqualTo(T0);
        assertThat(kept.size()).isEqualTo(Json.object().put("cpu", 4).put("memoryGb", 16));
        assertThat(kept.sizeSince()).isNull();
        assertThat(kept.utilisation().samples()).isEqualTo(337);
    }

    @Test
    void theLoopsWakesReadOnlyWhatIsDueAndAnOperatorsPassReadsEverything() {
        pass.run(null, false).block();
        clock.advance(Duration.ofHours(1));
        assertThat(pass.run(null, false).block()).as("an hour later nothing is due").isEqualTo(new UtilisationReport(0, 5, 0, 0, 0, 0));
        clock.advance(Duration.ofHours(22));
        UtilisationReport due = pass.run(null, false).block();
        assertThat(due.read()).as("a day less one wake later each is due").isEqualTo(3);
        assertThat(due.updated()).isEqualTo(2);
        assertThat(current("big").updatedAt()).isEqualTo(T0.plus(Duration.ofHours(23)));
        assertThat(pass.run(ACCOUNT, true).block().read()).as("forced").isEqualTo(3);
        assertThat(pass.run("210987654321", true).block()).as("an account without watches").isEqualTo(UtilisationReport.empty());
    }

    @Test
    void aChangeOfSizeSupersedesAndOnlyTheNewSizeIsJudged() {
        pass.run(null, false).block();
        String before = current("big").recommendationId();
        Watch big = watches.find(wfrn("big")).block();
        watches.save(big.toBuilder().desired(2, Json.object().put("cpu", 2).put("memoryGb", 8).put("name", "big")).build()).block();
        clock.advance(Duration.ofHours(2));

        UtilisationReport report = pass.run(ACCOUNT, true).block();
        assertThat(report.superseded()).isEqualTo(1);
        assertThat(current("big")).isNull();
        assertThat(recommendations.findAny(before).block().status()).isEqualTo(RecommendationStatus.SUPERSEDED);
        ResourceUtilisation kept = utilisation.find(wfrn("big")).block();
        assertThat(kept.sizeSince()).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(kept.size()).isEqualTo(Json.object().put("cpu", 2).put("memoryGb", 8));
        assertThat(kept.utilisation().samples()).isZero();

        // three days on: only the points since the change count, too few to judge
        clock.advance(Duration.ofDays(3));
        UtilisationReport later = pass.run(ACCOUNT, true).block();
        assertThat(metrics.windows.get(wfrn("big")).from()).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(later.opened()).isZero();
        assertThat(utilisation.find(wfrn("big")).block().sizeSince()).isEqualTo(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void aDismissalHoldsUntilItsDateThenTheRecommendationOpensAgain() {
        pass.run(null, false).block();
        Recommendation idle = current("idle");
        recommendations.save(idle.dismissed("dana", "batch window", T0.plus(Duration.ofDays(2)), T0)).block();

        clock.advance(Duration.ofDays(1));
        UtilisationReport held = pass.run(ACCOUNT, true).block();
        assertThat(held.opened()).isZero();
        assertThat(current("idle").status()).isEqualTo(RecommendationStatus.DISMISSED);

        clock.advance(Duration.ofDays(2));
        UtilisationReport reopened = pass.run(ACCOUNT, true).block();
        assertThat(reopened.opened()).isEqualTo(1);
        Recommendation again = current("idle");
        assertThat(again.recommendationId()).isEqualTo(idle.recommendationId());
        assertThat(again.status()).isEqualTo(RecommendationStatus.OPEN);
        assertThat(again.decidedBy()).isNull();
        assertThat(again.dismissedUntil()).isNull();
    }

    @Test
    void aResourceThatStopsQualifyingOrLeavesHasItsRecommendationSuperseded() {
        pass.run(null, false).block();
        String bigId = current("big").recommendationId();
        metrics.level(wfrn("idle"), 55, 60);
        watches.delete(wfrn("big")).block();
        clock.advance(Duration.ofHours(1));

        UtilisationReport report = pass.run(null, true).block();
        assertThat(report.superseded()).isEqualTo(2);
        assertThat(current("idle")).isNull();
        assertThat(recommendations.findAny(bigId).block().status()).isEqualTo(RecommendationStatus.SUPERSEDED);
        assertThat(utilisation.find(wfrn("big")).block()).as("forgotten with the resource").isNull();
    }

    @Test
    void aSpentBudgetIsWaitedOutAndAFailedReadCountsAsAnError() {
        metrics.fail(wfrn("big"), new MetricsPort.BudgetSpent("vsphere", "global", 1));
        metrics.fail(wfrn("busy"), new MetricsPort.Unavailable("the platform answered 500", null));
        metrics.fail(wfrn("idle"), new MetricsPort.NotSupported("no metrics read for this one"));

        UtilisationReport report = pass.run(ACCOUNT, false).block();
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.opened()).as("big, after the wait").isEqualTo(1);
        assertThat(current("big")).isNotNull();
        assertThat(report.skipped()).as("the volume, the VM in flight and the one without a read").isEqualTo(3);
        assertThat(utilisation.find(wfrn("busy")).block()).isNull();
    }

    @Test
    void tooFewPointsAreReadButNotJudged() {
        RightsizingRules strict = new RightsizingRules(new RightsizingRules.Thresholds(5, 10, 20, 30, 60, 10_000));
        UtilisationPass picky = new UtilisationPass(watches, new ResourceTypesPort() {
            @Override
            public Mono<TypeInfo> find(String name) {
                return Mono.justOrEmpty("vm".equals(name) ? VM : null);
            }

            @Override
            public Flux<TypeInfo> list() {
                return Flux.just(VM);
            }
        }, platformWithoutCatalog(), metrics, utilisation, recommendations, PRICES, strict,
                new UtilisationPass.Settings(Duration.ofHours(24), Duration.ofHours(1), Duration.ofDays(14), Duration.ofHours(1), "cpuPercent",
                        "memoryPercent", "cpu", "memoryGb", 1, 2, 1),
                clock);
        UtilisationReport report = picky.run(ACCOUNT, false).block();
        assertThat(report.read()).isEqualTo(3);
        assertThat(report.skipped()).as("three read without a judgement, the VM in flight and the volume").isEqualTo(5);
        assertThat(report.opened()).isZero();
        assertThat(utilisation.find(wfrn("idle")).block()).isNotNull();
    }

    private static PlatformPort platformWithoutCatalog() {
        PlatformPort platform = mock(PlatformPort.class);
        when(platform.listCatalog(anyString())).thenReturn(Mono.error(new IllegalStateException("the catalog is down")));
        return platform;
    }
}
