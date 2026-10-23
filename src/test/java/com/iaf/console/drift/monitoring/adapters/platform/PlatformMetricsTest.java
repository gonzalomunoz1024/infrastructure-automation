package com.iaf.console.drift.monitoring.adapters.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.iaf.console.drift.TestClock;
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformOperation;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.MetricsRequest;
import com.iaf.console.platform.model.MetricsResult;
import com.iaf.console.platform.model.PlatformError;

import reactor.core.publisher.Mono;

/**
 * The platform's metrics read: the request it sends with the account's own credential, the series
 * it answers, and what a spent budget, a type without a read, an unknown resource or a failing
 * platform become.
 */
class PlatformMetricsTest {

    static final String ACCOUNT = "123456789012";
    static final AccountScope SCOPE = AccountScope.of(ACCOUNT, "global", "dana");
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final TypeInfo VM = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing", false,
            false, Set.of(), List.of(), new TypeInfo.Metrics("vm.metrics", List.of("cpuPercent", "memoryPercent")), Map.of());
    static final TypeInfo DISK = new TypeInfo("disk", "disks", "storage", "vsphere", "disk.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing",
            false, false, Set.of(), List.of());
    static final MetricsTarget TARGET = new MetricsTarget("wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-01", ACCOUNT, "global", "vm", "web-01",
            "vm-01", "vm-1006", false);
    static final MetricsWindow WINDOW = new MetricsWindow(T0.minus(Duration.ofHours(1)), T0, Duration.ofMinutes(5));

    private final TestClock clock = new TestClock(T0);
    private final PlatformPort platform = mock(PlatformPort.class);
    private final List<String> tokensUsed = new ArrayList<>();
    private ObserveBudget budget;
    private PlatformMetrics metrics;

    @BeforeEach
    void setUp() {
        budget = new ObserveBudget(600, 100, 1.0);
        metrics = new PlatformMetrics(account -> Mono.just("token-of-" + account), token -> {
            tokensUsed.add(token);
            return platform;
        }, budget, clock);
    }

    private static PlatformException error(int status, String error, Integer retryAfter) {
        return new PlatformException(PlatformOperation.GET_RESOURCE_METRICS, status, new PlatformError(error, "corr-1", null, retryAfter, null, null), retryAfter,
                "corr-1");
    }

    private static MetricsResult result() {
        return new MetricsResult(List.of(
                new MetricsResult.Series("cpuPercent", "percent", Arrays.asList(new MetricsResult.Point(T0.minusSeconds(300), 12.5),
                        new MetricsResult.Point(T0, null), null)),
                new MetricsResult.Series("memoryPercent", "percent", null)), null, null, T0);
    }

    private Throwable failure(TypeInfo type, MetricsTarget target) {
        return catchThrowable(() -> metrics.getMetrics(SCOPE, target, type, WINDOW).block());
    }

    @Test
    void theReadAsksForTheWindowAndAnswersTheSeries() {
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.just(result()));
        ResourceMetrics m = metrics.getMetrics(SCOPE, TARGET, VM, WINDOW).block();

        ArgumentCaptor<MetricsRequest> request = ArgumentCaptor.forClass(MetricsRequest.class);
        verify(platform).getResourceMetrics(org.mockito.ArgumentMatchers.eq("vm-01"), request.capture());
        assertThat(request.getValue()).isEqualTo(new MetricsRequest(WINDOW.from(), WINDOW.to(), "PT5M"));
        assertThat(tokensUsed).containsExactly("token-of-" + ACCOUNT);
        assertThat(m.wfrn()).isEqualTo(TARGET.wfrn());
        assertThat(m.step()).isEqualTo("PT5M");
        assertThat(m.provider()).as("the type's, when the answer names none").isEqualTo("vsphere");
        assertThat(m.action()).isEqualTo("vm.metrics");
        assertThat(m.series("cpuPercent").orElseThrow().points()).hasSize(2);
        assertThat(m.series("cpuPercent").orElseThrow().values()).containsExactly(12.5);
        assertThat(m.series("memoryPercent").orElseThrow().points()).isEmpty();
        assertThat(metrics.supports(VM)).isTrue();
        assertThat(metrics.supports(DISK)).isFalse();
        assertThat(metrics.seriesOf(VM)).containsExactly("cpuPercent", "memoryPercent");

        // the client is kept while the account's token stays the same
        metrics.getMetrics(SCOPE, TARGET, VM, WINDOW).block();
        assertThat(tokensUsed).hasSize(1);
    }

    @Test
    void aTypeWithoutAReadOrAnImportedObjectIsNotSupported() {
        assertThat(failure(DISK, TARGET)).isInstanceOf(MetricsPort.NotSupported.class).hasMessageStartingWith("metrics.notSupported");
        MetricsTarget imported = new MetricsTarget(TARGET.wfrn(), ACCOUNT, "global", "vm", "web-01", null, "vm-1006", true);
        assertThat(failure(VM, imported)).isInstanceOf(MetricsPort.NotSupported.class);
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.error(error(404, "observe.notSupported", null)));
        assertThat(failure(VM, TARGET)).isInstanceOf(MetricsPort.NotSupported.class);
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.error(error(404, "resource.notFound", null)));
        Throwable unknown = failure(VM, TARGET);
        assertThat(unknown).isInstanceOf(NotFoundException.class).isNotInstanceOf(MetricsPort.NotSupported.class);
    }

    @Test
    void aSpentBudgetIsA429AndTheBudgetStaysEmptyForTheWait() {
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.error(error(429, "quota.exceeded", 40)));
        Throwable first = failure(VM, TARGET);
        assertThat(first).isInstanceOf(MetricsPort.BudgetSpent.class);
        assertThat(((MetricsPort.BudgetSpent) first).retryAfterSeconds()).isEqualTo(40);
        // the next read does not reach the platform until the wait is over
        Throwable second = failure(VM, TARGET);
        assertThat(second).isInstanceOf(MetricsPort.BudgetSpent.class);
        assertThat(((MetricsPort.BudgetSpent) second).retryAfterSeconds()).isBetween(1, 40);
        verify(platform, times(1)).getResourceMetrics(anyString(), any());

        clock.advance(Duration.ofSeconds(41));
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.just(result()));
        assertThat(metrics.getMetrics(SCOPE, TARGET, VM, WINDOW).block()).isNotNull();
    }

    @Test
    void aFailingPlatformOrAMissingCredentialIsUnavailable() {
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.error(error(500, "internal", null)));
        assertThat(failure(VM, TARGET)).isInstanceOf(MetricsPort.Unavailable.class).hasMessageContaining("500");
        when(platform.getResourceMetrics(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("connection reset")));
        assertThat(failure(VM, TARGET)).isInstanceOf(MetricsPort.Unavailable.class).hasMessageContaining("connection reset");

        PlatformPort untouched = mock(PlatformPort.class);
        PlatformMetrics noCredential = new PlatformMetrics(account -> Mono.empty(), token -> untouched, budget, clock);
        Throwable none = catchThrowable(() -> noCredential.getMetrics(SCOPE, TARGET, VM, WINDOW).block());
        assertThat(none).isInstanceOf(MetricsPort.Unavailable.class).hasMessageContaining(ACCOUNT);
        verify(untouched, never()).getResourceMetrics(anyString(), any());
    }
}
