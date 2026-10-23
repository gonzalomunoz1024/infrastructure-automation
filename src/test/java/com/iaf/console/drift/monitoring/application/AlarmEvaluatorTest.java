package com.iaf.console.drift.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWatchStore;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.monitoring.adapters.memory.InMemoryAlarmStore;
import com.iaf.console.drift.monitoring.domain.Alarm;
import com.iaf.console.drift.monitoring.domain.AlarmInput;
import com.iaf.console.drift.monitoring.domain.AlarmQuery;
import com.iaf.console.drift.monitoring.domain.AlarmState;
import com.iaf.console.drift.monitoring.domain.Comparison;
import com.iaf.console.drift.monitoring.domain.MetricPoint;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.AlarmStorePort;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The evaluation of every alarm (one read per resource, over the longest window its alarms need),
 * what a resource that left or a failed read does to its alarms, and the alarms API's own rules.
 */
class AlarmEvaluatorTest {

    static final String ACCOUNT = "123456789012";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final TypeInfo VM = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing", false,
            false, Set.of(), List.of(), new TypeInfo.Metrics("vm.metrics", List.of("cpuPercent", "memoryPercent")), Map.of());
    static final TypeInfo DISK = new TypeInfo("disk", "disks", "storage", "vsphere", "disk.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing",
            false, false, Set.of(), List.of());

    /** Series of one value per resource and series, a point a minute; failures by wfrn. */
    static final class FakeMetrics implements MetricsPort {
        final Map<String, Map<String, Double>> values = new ConcurrentHashMap<>();
        final Map<String, RuntimeException> failing = new ConcurrentHashMap<>();
        final List<String> reads = new ArrayList<>();
        final Map<String, MetricsWindow> windows = new ConcurrentHashMap<>();

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
                if (failing.containsKey(target.wfrn())) {
                    return Mono.error(failing.get(target.wfrn()));
                }
                List<MetricSeries> series = new ArrayList<>();
                values.getOrDefault(target.wfrn(), Map.of()).forEach((name, value) -> {
                    List<MetricPoint> points = new ArrayList<>();
                    for (Instant at = window.from().plus(window.step()); !at.isAfter(window.to()); at = at.plus(window.step())) {
                        points.add(new MetricPoint(at, value));
                    }
                    series.add(new MetricSeries(name, "percent", points));
                });
                return Mono.just(new ResourceMetrics(target.wfrn(), window.from(), window.to(), window.stepText(), series, "vsphere", "vm.metrics"));
            });
        }
    }

    /** A state service that knows nothing: every resource here is on the watch list. */
    static StateServicePort noState() {
        StateServicePort state = mock(StateServicePort.class);
        when(state.getResource(anyString())).thenReturn(Mono.error(new StateServicePort.StateError(404, "resource.notFound", null)));
        return state;
    }

    private final TestClock clock = new TestClock(T0);
    private final InMemoryAlarmStore alarms = new InMemoryAlarmStore();
    private final InMemoryWatchStore watches = new InMemoryWatchStore();
    private final FakeMetrics metrics = new FakeMetrics();
    private AlarmEvaluator evaluator;
    private AlarmService service;

    static String wfrn(String type, String id) {
        return "wfrn:wf:compute:global:" + ACCOUNT + ":" + type + "/" + id;
    }

    @BeforeEach
    void setUp() {
        ResourceTypesPort types = new ResourceTypesPort() {
            @Override
            public Mono<TypeInfo> find(String name) {
                return Mono.justOrEmpty("vm".equals(name) ? VM : "disk".equals(name) ? DISK : null);
            }

            @Override
            public Flux<TypeInfo> list() {
                return Flux.just(VM, DISK);
            }
        };
        MetricsTargets targets = new MetricsTargets(watches, noState(), types, metrics);
        evaluator = new AlarmEvaluator(alarms, targets, types, metrics, 2, clock);
        service = new AlarmService(alarms, targets, metrics, clock);
        for (String id : List.of("vm-a", "vm-b")) {
            watches.save(Watch.builder(wfrn("vm", id), ACCOUNT, "global", "vm").name(id).registryId(id).build()).block();
        }
        watches.save(Watch.builder(wfrn("disk", "d-1"), ACCOUNT, "global", "disk").name("d-1").build()).block();
        metrics.values.put(wfrn("vm", "vm-a"), Map.of("cpuPercent", 90.0, "memoryPercent", 40.0));
        metrics.values.put(wfrn("vm", "vm-b"), Map.of("cpuPercent", 10.0, "memoryPercent", 70.0));
    }

    private Alarm put(String alarmId, String resource, String metric, String comparison, double threshold, Integer window) {
        return service.put(SCOPE, alarmId, new AlarmInput(wfrn("vm", resource), null, metric, comparison, threshold, window, null)).block().alarm();
    }

    private Alarm get(String alarmId) {
        return alarms.find(SCOPE, alarmId).block();
    }

    @Test
    void eachResourceIsReadOnceOverTheLongestWindowOfItsAlarms() {
        put("a-cpu", "vm-a", "cpuPercent", "above", 80, 5);
        put("a-memory", "vm-a", "memoryPercent", "above", 80, 30);
        put("b-cpu", "vm-b", "cpuPercent", "below", 20, null);
        clock.advance(Duration.ofMinutes(1));

        AlarmsReport report = evaluator.evaluateAll().block();
        assertThat(report).isEqualTo(new AlarmsReport(3, 2, 0, 0, 0));
        assertThat(metrics.reads).containsExactlyInAnyOrder(wfrn("vm", "vm-a"), wfrn("vm", "vm-b"));
        MetricsWindow window = metrics.windows.get(wfrn("vm", "vm-a"));
        assertThat(Duration.between(window.from(), window.to())).isEqualTo(Duration.ofMinutes(30));
        assertThat(window.step()).isEqualTo(Duration.ofMinutes(1));
        assertThat(get("a-cpu").state()).isEqualTo(AlarmState.ALARM);
        assertThat(get("a-cpu").lastValue()).isEqualTo(90.0);
        assertThat(get("a-memory").state()).isEqualTo(AlarmState.OK);
        assertThat(get("b-cpu").state()).isEqualTo(AlarmState.ALARM);
        assertThat(get("b-cpu").lastEvaluatedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

        // the CPU of vm-a drops: the alarm clears
        metrics.values.put(wfrn("vm", "vm-a"), Map.of("cpuPercent", 20.0, "memoryPercent", 40.0));
        clock.advance(Duration.ofMinutes(1));
        AlarmsReport next = evaluator.evaluateAll().block();
        assertThat(next.cleared()).isEqualTo(1);
        assertThat(next.fired()).isZero();
        assertThat(get("a-cpu").state()).isEqualTo(AlarmState.OK);
        assertThat(get("b-cpu").stateSince()).as("still firing since the first evaluation").isEqualTo(T0.plus(Duration.ofMinutes(1)));
    }

    @Test
    void aResourceThatLeftHasInsufficientAlarmsAndAFailedReadLeavesThemAsTheyWere() {
        put("a-cpu", "vm-a", "cpuPercent", "above", 80, 5);
        put("b-cpu", "vm-b", "cpuPercent", "below", 20, 5);
        clock.advance(Duration.ofMinutes(1));
        evaluator.evaluateAll().block();
        Alarm before = get("b-cpu");

        watches.delete(wfrn("vm", "vm-a")).block();
        metrics.failing.put(wfrn("vm", "vm-b"), new MetricsPort.BudgetSpent("vsphere", "global", 30));
        clock.advance(Duration.ofMinutes(1));
        AlarmsReport report = evaluator.evaluateAll().block();
        assertThat(report).isEqualTo(new AlarmsReport(1, 0, 0, 1, 1));
        assertThat(get("a-cpu").state()).isEqualTo(AlarmState.INSUFFICIENT);
        assertThat(get("a-cpu").lastValue()).isNull();
        assertThat(get("b-cpu")).isEqualTo(before);
    }

    @Test
    void aSeriesTheReadDoesNotAnswerIsInsufficient() {
        put("a-cpu", "vm-a", "cpuPercent", "above", 80, 5);
        metrics.values.put(wfrn("vm", "vm-a"), Map.of("memoryPercent", 40.0));
        clock.advance(Duration.ofMinutes(1));
        assertThat(evaluator.evaluateAll().block().insufficient()).isEqualTo(1);
        assertThat(evaluator.evaluateAll().block().evaluated()).isEqualTo(1);
        assertThat(alarms.findAll().collectList().block()).hasSize(1);
        assertThat(evaluator.evaluateAll().block()).as("an empty pass").isNotNull();
    }

    @Test
    void anAlarmNeedsAKnownSeriesOfAResourceOfTheAccountWithAMetricsRead() {
        Throwable unknownMetric = org.assertj.core.api.Assertions.catchThrowable(() -> put("x", "vm-a", "gpuPercent", "above", 1, null));
        assertThat(unknownMetric).isInstanceOf(InvalidInputException.class);
        Throwable noRead = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.put(SCOPE, "x", new AlarmInput(wfrn("disk", "d-1"), null, "cpuPercent", "above", 1.0, null, null)).block());
        assertThat(noRead).isInstanceOf(MetricsPort.NotSupported.class).hasMessageStartingWith("metrics.notSupported");
        Throwable elsewhere = org.assertj.core.api.Assertions.catchThrowable(() -> service.put(SCOPE, "x",
                new AlarmInput("wfrn:wf:compute:global:210987654321:vm/vm-z", null, "cpuPercent", "above", 1.0, null, null)).block());
        assertThat(elsewhere).isInstanceOf(NotFoundException.class);
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(() -> put("x", "vm-gone", "cpuPercent", "above", 1, null));
        assertThat(missing).isInstanceOf(NotFoundException.class);
        Throwable badId = org.assertj.core.api.Assertions.catchThrowable(() -> put("not ok", "vm-a", "cpuPercent", "above", 1, null));
        assertThat(badId).isInstanceOf(IllegalArgumentException.class);
        assertThat(alarms.findAll().collectList().block()).isEmpty();

        Alarm created = put("x", "vm-a", "cpuPercent", "above", 1, null);
        assertThat(created.createdBy()).as("the principal, when the BFF sends no actor").isEqualTo("dana");
        assertThat(created.windowMinutes()).isEqualTo(10);
        Alarm byActor = service.put(SCOPE, "y", new AlarmInput(wfrn("vm", "vm-a"), " ", "cpuPercent", "above", 1.0, null, "u-123")).block().alarm();
        assertThat(byActor.createdBy()).isEqualTo("u-123");
        assertThat(byActor.name()).isNull();
        assertThat(service.put(SCOPE, "y", new AlarmInput(wfrn("vm", "vm-b"), "b", "cpuPercent", "below", 5.0, 60, "u-456")).block().created()).isFalse();
        assertThat(get("y").createdBy()).as("the creator stays").isEqualTo("u-123");
        assertThat(get("y").wfrn()).isEqualTo(wfrn("vm", "vm-b"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.get(SCOPE, "z").block()).isInstanceOf(NotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.delete(SCOPE, "z").block()).isInstanceOf(NotFoundException.class);
        service.delete(SCOPE, "y").block();
        assertThat(get("y")).isNull();
    }

    @Test
    void anAlarmChangedDuringThePassIsLeftForTheNextOne() {
        put("a-cpu", "vm-a", "cpuPercent", "above", 80, 5);
        clock.advance(Duration.ofMinutes(1));
        // the evaluation read revision 1; a person writes revision 2 before it stores its answer
        AlarmStorePort racing = new AlarmStorePort() {
            @Override
            public Mono<Alarm> find(AccountScope scope, String alarmId) {
                return alarms.find(scope, alarmId);
            }

            @Override
            public Mono<Page<Alarm>> list(AccountScope scope, AlarmQuery query) {
                return alarms.list(scope, query);
            }

            @Override
            public Flux<Alarm> findAll() {
                return alarms.findAll();
            }

            @Override
            public Mono<Alarm> save(Alarm alarm) {
                if (alarm.lastEvaluatedAt() == null) {
                    return alarms.save(alarm);
                }
                return alarms.find(SCOPE, alarm.alarmId())
                        .flatMap(stored -> alarms.save(stored.changed("vm", stored.wfrn(), "renamed", stored.metric(), stored.comparison(), 99, 5,
                                clock.instant())))
                        .then(alarms.save(alarm));
            }

            @Override
            public Mono<Boolean> delete(AccountScope scope, String alarmId) {
                return alarms.delete(scope, alarmId);
            }
        };
        ResourceTypesPort types = new ResourceTypesPort() {
            @Override
            public Mono<TypeInfo> find(String name) {
                return Mono.just(VM);
            }

            @Override
            public Flux<TypeInfo> list() {
                return Flux.just(VM);
            }
        };
        AlarmEvaluator raced = new AlarmEvaluator(racing, new MetricsTargets(watches, noState(), types, metrics), types, metrics, 1, clock);
        AlarmsReport report = raced.evaluateAll().block();
        assertThat(report).isEqualTo(AlarmsReport.empty());
        assertThat(get("a-cpu").name()).isEqualTo("renamed");
        assertThat(get("a-cpu").state()).as("the person's change stands").isEqualTo(AlarmState.INSUFFICIENT);
        assertThat(get("a-cpu").comparison()).isEqualTo(Comparison.ABOVE);
    }
}
