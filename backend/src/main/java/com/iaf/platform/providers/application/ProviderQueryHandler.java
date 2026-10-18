package com.iaf.platform.providers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.domain.ActionInfo;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDetail;
import com.iaf.platform.providers.domain.ProviderDetail.Daily;
import com.iaf.platform.providers.domain.ProviderDetail.ErrorCount;
import com.iaf.platform.providers.domain.ProviderDetail.PerAction;
import com.iaf.platform.providers.domain.ProviderHealthRule;
import com.iaf.platform.providers.domain.ProviderSummary;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.providers.ports.inbound.ProviderQueries.Use;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side: the definitions for the checks, the interpreter and the registry, plus the admin
 * view (list, detail with two weeks of telemetry, alerts). Health is judged by
 * {@link ProviderHealthRule} over the last 24 hours of {@link TelemetryPort} samples. A lookup
 * made for a job ({@link Use} in the context) gets the version an active provider release routes
 * the job to; every other read sees the current version.
 */
@Primary
@Service
@RequiredArgsConstructor
public class ProviderQueryHandler implements ProviderQueries {

    static final int DAILY_DAYS = 14;
    static final int TOP_ERRORS = 5;

    private final ProviderStorePort store;
    private final TelemetryPort telemetry;
    private final ChangeRequestPort changeRequests;
    private final ProviderAlertEvaluator alerts;
    private final Clock clock;
    private final ReleaseQueries releases;

    @Override
    public Mono<ProviderDefinition> provider(String name) {
        return Mono.deferContextual(context -> {
            Mono<StoredProvider> current = Mono.defer(() -> Mono.justOrEmpty(store.get(name)));
            Use use = Use.of(context);
            if (use == null) {
                return current.map(StoredProvider::definition);
            }
            return releases.routeProvider(name, use.jobId(), use.environment(), use.dryRun())
                    .flatMap(version -> Mono.justOrEmpty(store.version(name, version)))
                    .switchIfEmpty(current)
                    .map(StoredProvider::definition);
        });
    }

    @Override
    public Flux<ProviderDefinition> all() {
        return Flux.defer(() -> Flux.fromIterable(store.all())).map(StoredProvider::definition);
    }

    public Flux<ProviderSummary> list() {
        return Flux.defer(() -> {
            Instant now = clock.instant();
            Map<String, List<CallSample>> byProvider = new HashMap<>();
            for (CallSample s : telemetry.samples(null, now.minus(ProviderHealthRule.WINDOW))) {
                byProvider.computeIfAbsent(s.provider(), k -> new ArrayList<>()).add(s);
            }
            return Flux.fromIterable(store.all()).map(p -> {
                ProviderDefinition def = p.definition();
                Health health = ProviderHealthRule.judge(byProvider.getOrDefault(def.name(), List.of()), def.slo(), now);
                return ProviderSummary.of(def, health);
            });
        });
    }

    public Mono<ProviderDetail> detail(String name) {
        return Mono.fromCallable(() -> {
            StoredProvider p = store.get(name).orElseThrow(() -> new NotFoundException("Provider", name));
            ProviderDefinition def = p.definition();
            Instant now = clock.instant();
            List<CallSample> twoWeeks = telemetry.samples(name, now.minus(Duration.ofDays(DAILY_DAYS)));
            Instant dayAgo = now.minus(ProviderHealthRule.WINDOW);
            List<CallSample> day = twoWeeks.stream().filter(s -> s.at() != null && !s.at().isBefore(dayAgo)).toList();
            Health health = ProviderHealthRule.judge(day, def.slo(), now);
            List<ActionInfo> actions = def.actions().stream().map(a -> ActionInfo.of(def, a)).toList();
            List<ChangeRequest> crs = changeRequests.all().stream()
                    .filter(cr -> cr.kind() == ChangeRequest.Kind.PROVIDER && name.equals(cr.name()))
                    .sorted(Comparator.comparing(ChangeRequest::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            return ProviderDetail.of(ProviderSummary.of(def, health), p.yaml(), actions, daily(twoWeeks, now), perAction(twoWeeks), crs);
        });
    }

    public Flux<ProviderAlert> alerts(String name) {
        return Flux.defer(() -> {
            if (store.get(name).isEmpty()) {
                throw new NotFoundException("Provider", name);
            }
            return Flux.fromIterable(alerts.alerts(name));
        });
    }

    /** The last {@value #DAILY_DAYS} days (UTC), oldest first, days without calls included. */
    static List<Daily> daily(List<CallSample> samples, Instant now) {
        Map<LocalDate, List<CallSample>> byDate = new HashMap<>();
        for (CallSample s : samples) {
            if (s.at() != null) {
                byDate.computeIfAbsent(s.at().atZone(ZoneOffset.UTC).toLocalDate(), k -> new ArrayList<>()).add(s);
            }
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<Daily> out = new ArrayList<>();
        for (int d = DAILY_DAYS - 1; d >= 0; d--) {
            LocalDate date = today.minusDays(d);
            List<CallSample> day = byDate.getOrDefault(date, List.of());
            out.add(new Daily(date.toString(), day.size(), ProviderHealthRule.successRate(day), ProviderHealthRule.percentile(day, 50),
                    ProviderHealthRule.percentile(day, 95)));
        }
        return out;
    }

    static List<PerAction> perAction(List<CallSample> samples) {
        Map<String, List<CallSample>> byAction = new TreeMap<>();
        for (CallSample s : samples) {
            byAction.computeIfAbsent(s.action() == null ? "?" : s.action(), k -> new ArrayList<>()).add(s);
        }
        List<PerAction> out = new ArrayList<>();
        byAction.forEach((action, list) -> {
            Map<String, Integer> errors = new HashMap<>();
            for (CallSample s : list) {
                if (!s.ok()) {
                    errors.merge(s.errorClass() == null ? "error" : s.errorClass(), 1, Integer::sum);
                }
            }
            List<ErrorCount> top = errors.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(TOP_ERRORS)
                    .map(e -> new ErrorCount(e.getKey(), e.getValue()))
                    .toList();
            out.add(new PerAction(action, list.size(), ProviderHealthRule.successRate(list), ProviderHealthRule.percentile(list, 50),
                    ProviderHealthRule.percentile(list, 95), top));
        });
        return out;
    }
}
