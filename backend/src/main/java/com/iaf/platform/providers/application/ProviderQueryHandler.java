package com.iaf.platform.providers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.providers.domain.ActionInfo;
import com.iaf.platform.providers.domain.Health;
import com.iaf.platform.providers.domain.ProviderAlert;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;
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
 *
 * <p>The admin view shows the caller what {@link AccessRules#canSeeProvider} allows: an admin
 * every provider, anyone else the providers of their teams and the unowned ones; a provider the
 * caller may not see answers as if it did not exist (404). The definitions for the checks, the
 * interpreter and the registry ({@link #provider(String)}, {@link #all()}) are not scoped: the
 * step library needs every provider's actions.
 */
@Primary
@Service
@RequiredArgsConstructor
public class ProviderQueryHandler implements ProviderQueries {

    static final int DAILY_DAYS = ProviderHealthRule.TREND_DAYS;
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

    @Override
    public Optional<String> ownerOf(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return store.get(name).map(p -> p.definition().owner()).filter(owner -> !owner.isBlank());
    }

    @Override
    public Optional<String> currentVersion(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return store.get(name).map(p -> p.definition().metadata().version());
    }

    public Flux<ProviderSummary> list() {
        return CurrentIdentity.current().flatMapMany(who -> Flux.defer(() -> {
            Instant now = clock.instant();
            Map<String, List<CallSample>> byProvider = new HashMap<>();
            // two weeks: the band is judged over the last day, the error trend beside it spans the whole fortnight
            for (CallSample s : telemetry.samples(null, now.minus(Duration.ofDays(DAILY_DAYS)))) {
                byProvider.computeIfAbsent(s.provider(), k -> new ArrayList<>()).add(s);
            }
            return Flux.fromIterable(store.all())
                    .filter(p -> AccessRules.canSeeProvider(who, p.definition().owner()))
                    .map(p -> {
                        ProviderDefinition def = p.definition();
                        Health health = ProviderHealthRule.judge(byProvider.getOrDefault(def.name(), List.of()), def, now);
                        return ProviderSummary.of(def, health);
                    });
        }));
    }

    public Mono<ProviderDetail> detail(String name) {
        return CurrentIdentity.current().flatMap(who -> Mono.fromCallable(() -> {
            StoredProvider p = visible(who, name);
            ProviderDefinition def = p.definition();
            Instant now = clock.instant();
            List<CallSample> twoWeeks = telemetry.samples(name, now.minus(Duration.ofDays(DAILY_DAYS)));
            Health health = ProviderHealthRule.judge(twoWeeks, def, now);
            List<ActionInfo> actions = def.actions().stream().map(a -> ActionInfo.of(def, a)).toList();
            List<ChangeRequest> crs = changeRequests.all().stream()
                    .filter(cr -> cr.kind() == ChangeRequest.Kind.PROVIDER && name.equals(cr.name()))
                    .sorted(Comparator.comparing(ChangeRequest::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            return ProviderDetail.of(ProviderSummary.of(def, health), def, p.yaml(), actions, daily(twoWeeks, now), perAction(twoWeeks, def, now), crs);
        }));
    }

    public Flux<ProviderAlert> alerts(String name) {
        return CurrentIdentity.current().flatMapMany(who -> Flux.defer(() -> {
            visible(who, name);
            return Flux.fromIterable(alerts.alerts(name));
        }));
    }

    /** The stored provider, when there is one and the caller may see it; a hidden one is as unknown as a missing one. */
    private StoredProvider visible(Identity who, String name) {
        return store.get(name)
                .filter(p -> AccessRules.canSeeProvider(who, p.definition().owner()))
                .orElseThrow(() -> new NotFoundException("Provider", name));
    }

    /**
     * The last {@value #DAILY_DAYS} days as the 24-hour windows of {@link ProviderHealthRule#dailyWindows}
     * (the same ones the summary's error trend counts), each with its calls, failed calls and
     * rates; a window without calls has no rates.
     */
    static List<Daily> daily(List<CallSample> samples, Instant now) {
        Instant from = now.minus(Duration.ofDays(DAILY_DAYS));
        List<List<CallSample>> windows = ProviderHealthRule.dailyWindows(samples, now);
        List<Daily> out = new ArrayList<>();
        for (int i = 0; i < DAILY_DAYS; i++) {
            List<CallSample> w = windows.get(i);
            String start = from.plus(Duration.ofDays(i)).toString();
            int errors = (int) w.stream().filter(s -> !s.ok()).count();
            out.add(w.isEmpty()
                    ? new Daily(start, 0, 0, null, null, null)
                    : new Daily(start, w.size(), errors, ProviderHealthRule.successRate(w), ProviderHealthRule.percentile(w, 50),
                            ProviderHealthRule.percentile(w, 95)));
        }
        return out;
    }

    /**
     * Every action with calls in the samples, by id: its calls over the whole span, the SLO it is
     * judged by (its own or the provider's) and its band over the last 24 hours against that SLO.
     */
    static List<PerAction> perAction(List<CallSample> samples, ProviderDefinition def, Instant now) {
        Map<String, List<CallSample>> byAction = new TreeMap<>();
        for (CallSample s : samples) {
            byAction.computeIfAbsent(s.action() == null ? "?" : s.action(), k -> new ArrayList<>()).add(s);
        }
        List<PerAction> out = new ArrayList<>();
        byAction.forEach((action, list) -> {
            Slo slo = def.sloOf(action);
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
                    ProviderHealthRule.percentile(list, 95), top, slo, ProviderHealthRule.band(list, slo, now)));
        });
        return out;
    }
}
