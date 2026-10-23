package com.iaf.console.identity.quotas.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.quotas.domain.Quota;
import com.iaf.console.identity.quotas.domain.QuotaKeys;
import com.iaf.console.identity.quotas.domain.QuotaOverride;
import com.iaf.console.identity.quotas.domain.QuotaRequest;
import com.iaf.console.identity.quotas.ports.outbound.PlatformQuotaPort;
import com.iaf.console.identity.quotas.ports.outbound.QuotaStore;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reads on quotas: the account's overrides over the defaults, {@code jobs-per-minute} read
 * through from the platform's bucket, {@code access-keys} counted here, {@code resources.<type>}
 * for every service the catalog knows ({@code used} is the state service's count; this service
 * serves the limit, so it answers 0 until the state service reports).
 */
public final class QuotaQueries {

    private static final Logger log = LoggerFactory.getLogger(QuotaQueries.class);
    private static final String KIND = "quota";

    private final QuotaStore quotas;
    private final PlatformQuotaPort platform;
    private final AccessKeyStore keys;
    private final ActionCatalogPort catalog;
    private final AccessGuard guard;
    private final IamSupport support;
    private final Clock clock;

    public QuotaQueries(QuotaStore quotas, PlatformQuotaPort platform, AccessKeyStore keys, ActionCatalogPort catalog, AccessGuard guard, IamSupport support,
            Clock clock) {
        this.quotas = quotas;
        this.platform = platform;
        this.keys = keys;
        this.catalog = catalog;
        this.guard = guard;
        this.support = support;
        this.clock = clock;
    }

    public Mono<List<Quota>> list(Caller caller, String accountId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "quota:list", support.anyResource(accountId, KIND)).thenReturn(scope))
                .flatMap(scope -> Mono.zip(quotas.findOverrides(scope).collectMap(QuotaOverride::key), catalog.getCatalog())
                        .flatMap(t -> {
                            Map<String, QuotaOverride> overrides = t.getT1();
                            List<String> keysToServe = new ArrayList<>();
                            keysToServe.add(QuotaKeys.JOBS_PER_MINUTE);
                            keysToServe.add(QuotaKeys.ACCESS_KEYS);
                            for (String service : t.getT2().services()) {
                                if (!com.iaf.console.identity.iam.domain.ActionCatalog.CONSOLE_SERVICES.containsKey(service)) {
                                    keysToServe.add(QuotaKeys.RESOURCES_PREFIX + service);
                                }
                            }
                            overrides.keySet().stream().filter(k -> !keysToServe.contains(k)).sorted().forEach(keysToServe::add);
                            return Flux.fromIterable(keysToServe).concatMap(key -> quota(scope, key, overrides.get(key))).collectList();
                        }));
    }

    public Mono<Quota> get(Caller caller, String accountId, String key) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "quota:get", support.resource(accountId, KIND, key)).thenReturn(scope))
                .flatMap(scope -> {
                    if (!QuotaKeys.isValid(key)) {
                        return Mono.error(NotFoundException.of("quota", key));
                    }
                    return quotas.findOverride(scope, key).map(List::of).defaultIfEmpty(List.of())
                            .flatMap(found -> quota(scope, key, found.isEmpty() ? null : found.get(0)));
                });
    }

    /** One quota, as served: the override or the default, the platform's bucket for jobs, and the open request. */
    public Mono<Quota> quota(AccountScope scope, String key, QuotaOverride override) {
        Mono<Quota> base;
        if (QuotaKeys.isPlatform(key)) {
            base = platform.getBucket(scope.accountId())
                    .map(bucket -> new Quota(key, bucket.limitPerMinute(), bucket.burst(), Math.max(0, bucket.limitPerMinute() - bucket.available()),
                            override != null ? Quota.Source.OVERRIDE : Quota.Source.PLATFORM, null))
                    .onErrorResume(e -> {
                        log.warn("the platform's bucket of {} could not be read: {}", scope.accountId(), e.toString());
                        return Mono.just(override != null ? new Quota(key, override.limit(), override.burst(), 0, Quota.Source.OVERRIDE, null)
                                : new Quota(key, 60, 60, 0, Quota.Source.DEFAULT, null));
                    });
        } else if (QuotaKeys.ACCESS_KEYS.equals(key)) {
            base = keys.findAll(scope).filter(k -> k.usable(clock.instant())).count()
                    .map(used -> override != null ? new Quota(key, override.limit(), override.burst(), used.intValue(), Quota.Source.OVERRIDE, null)
                            : new Quota(key, QuotaKeys.defaultLimit(key), null, used.intValue(), Quota.Source.DEFAULT, null));
        } else {
            base = Mono.just(override != null ? new Quota(key, override.limit(), override.burst(), 0, Quota.Source.OVERRIDE, null)
                    : new Quota(key, QuotaKeys.defaultLimit(key), null, 0, Quota.Source.DEFAULT, null));
        }
        return base.flatMap(q -> quotas.findOpenRequest(scope, key).map(r -> q.withPendingRequest(r.requestId())).defaultIfEmpty(q));
    }

    public Mono<QuotaRequest> getRequest(Caller caller, String accountId, String requestId) {
        return support.scope(caller, accountId)
                .flatMap(scope -> guard.require(caller, accountId, "quota:get", support.resource(accountId, KIND, requestId)).thenReturn(scope))
                .flatMap(scope -> quotas.findRequest(scope, requestId).switchIfEmpty(Mono.error(NotFoundException.of("quota request", requestId))));
    }

    /** The defaults every account starts with, for the README and the tests. */
    public static Map<String, Integer> defaults() {
        Map<String, Integer> defaults = new TreeMap<>();
        defaults.put(QuotaKeys.ACCESS_KEYS, QuotaKeys.DEFAULT_ACCESS_KEYS);
        defaults.put(QuotaKeys.RESOURCES_PREFIX + "<type>", QuotaKeys.DEFAULT_RESOURCES);
        return defaults;
    }
}
