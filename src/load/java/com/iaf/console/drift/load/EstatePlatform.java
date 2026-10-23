package com.iaf.console.drift.load;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.DiscoverRequest;
import com.iaf.console.platform.model.ObserveBatchRequest;
import com.iaf.console.platform.model.ObserveBatchResult;
import com.iaf.console.platform.model.ObserveListPage;
import com.iaf.console.platform.model.ObserveListRequest;
import com.iaf.console.platform.model.ObserveResult;
import com.iaf.console.platform.model.Region;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * The platform's observe operations over the synthetic estate (P-09), counting every call: an
 * {@code :observe-list} page is the account's group from the cursor's offset, a discover page the
 * same over every account's group of the type and environment, a batch one result per wfrn. Every
 * provider answers what the watch list wants (no drift), after a latency on the virtual clock. The
 * platform's own bucket per provider and region (P-09: 5 reads a second unless the provider sets
 * more) answers 429 with Retry-After when it is empty, so a scan that outruns it is seen.
 */
final class EstatePlatform {

    private final Estate estate;
    private final Scheduler time;
    private final Clock clock;
    private final Duration pageLatency;
    private final Duration batchLatency;
    private final ObserveBudget limit;
    private final PlatformCalls calls;
    private final Map<String, long[]> discoverOffsets = new HashMap<>();

    EstatePlatform(Estate estate, Scheduler time, Clock clock, Duration pageLatency, Duration batchLatency, ObserveBudget limit, PlatformCalls calls) {
        this.estate = estate;
        this.time = time;
        this.clock = clock;
        this.pageLatency = pageLatency;
        this.batchLatency = batchLatency;
        this.limit = limit;
        this.calls = calls;
    }

    /** The port the scanner calls; everything but the observe operations and the region list is refused. */
    PlatformPort port() {
        return (PlatformPort) Proxy.newProxyInstance(PlatformPort.class.getClassLoader(), new Class<?>[] {PlatformPort.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "observeList" -> observeList((String) args[0], (ObserveListRequest) args[1]);
                case "discover" -> discover((String) args[0], (DiscoverRequest) args[1]);
                case "observeResources" -> observeResources((ObserveBatchRequest) args[0]);
                case "observeResourceByWfrn" -> observeOne((String) args[0]);
                case "listRegions" -> Mono.just(List.of(new Region(Estate.REGION, "Global", null, "wf", "active", Estate.ENVS, true)));
                case "toString" -> "the load estate's platform";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName() + " is not part of a scan");
            };
        });
    }

    private Mono<ObserveListPage> observeList(String type, ObserveListRequest request) {
        Optional<Mono<ObserveListPage>> refused = refuse();
        if (refused.isPresent()) {
            return refused.get();
        }
        Estate.Group g = estate.group(request.account(), type, request.environment());
        long from = request.cursor() == null ? 0 : Long.parseLong(request.cursor());
        int size = request.pageSize() == null ? 500 : request.pageSize();
        List<ObserveListPage.Item> items = new ArrayList<>();
        String next = null;
        if (g != null) {
            long to = Math.min(g.size(), from + size);
            for (long i = g.start() + from; i < g.start() + to; i++) {
                Estate.Resource r = estate.resource(i);
                // an imported object has no registry record: the account-filtered list does not answer it
                if (!r.imported()) {
                    items.add(new ObserveListPage.Item(estate.providerId(i), estate.wfrn(i), "reg-" + Long.toString(i, 36), estate.kind(g).attributes(), true));
                }
            }
            next = to < g.size() ? Long.toString(to) : null;
        }
        calls.page(g, request.cursor() == null, next == null, items.size(), clock.instant());
        return answer(new ObserveListPage(items, next, Estate.PROVIDER, type + ".list", clock.instant()), pageLatency);
    }

    private Mono<ObserveListPage> discover(String type, DiscoverRequest request) {
        Optional<Mono<ObserveListPage>> refused = refuse();
        if (refused.isPresent()) {
            return refused.get();
        }
        List<Estate.Group> groups = estate.discoverList(type, request.environment());
        long[] offsets = discoverOffsets.computeIfAbsent(type + "|" + request.environment(), k -> {
            long[] o = new long[groups.size() + 1];
            for (int n = 0; n < groups.size(); n++) {
                o[n + 1] = o[n] + groups.get(n).size();
            }
            return o;
        });
        long total = offsets[groups.size()];
        long from = request.cursor() == null ? 0 : Long.parseLong(request.cursor());
        int size = request.pageSize() == null ? 500 : request.pageSize();
        long to = Math.min(total, from + size);
        List<ObserveListPage.Item> items = new ArrayList<>();
        int n = groupAt(offsets, from);
        for (long at = from; at < to; at++) {
            while (at >= offsets[n + 1]) {
                n++;
            }
            long i = groups.get(n).start() + (at - offsets[n]);
            boolean imported = estate.resource(i).imported();
            items.add(new ObserveListPage.Item(estate.providerId(i), imported ? null : estate.wfrn(i), null, estate.kind(groups.get(n)).attributes(), !imported));
        }
        String next = to < total ? Long.toString(to) : null;
        calls.discoverPage(type + "|" + request.environment(), request.cursor() == null, next == null, items.size(), clock.instant());
        return answer(new ObserveListPage(items, next, Estate.PROVIDER, type + ".list", clock.instant()), pageLatency);
    }

    private static int groupAt(long[] offsets, long at) {
        int lo = 0;
        int hi = offsets.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= at) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(0, lo);
    }

    private Mono<ObserveBatchResult> observeResources(ObserveBatchRequest request) {
        Optional<Mono<ObserveBatchResult>> refused = refuse();
        if (refused.isPresent()) {
            return refused.get();
        }
        List<ObserveResult> results = new ArrayList<>();
        for (String wfrn : request.wfrns()) {
            results.add(result(wfrn));
        }
        calls.batch(results.size(), clock.instant());
        return answer(new ObserveBatchResult(results), batchLatency);
    }

    private Mono<ObserveResult> observeOne(String wfrn) {
        Optional<Mono<ObserveResult>> refused = refuse();
        if (refused.isPresent()) {
            return refused.get();
        }
        calls.single(clock.instant());
        return answer(result(wfrn), batchLatency);
    }

    private ObserveResult result(String wfrn) {
        long i = Estate.indexOf(wfrn);
        Instant at = clock.instant();
        if (i < 0 || i >= estate.size()) {
            return new ObserveResult("not_found", null, null, Estate.PROVIDER, "get", null, Estate.REGION, null, at, 1L, null, wfrn);
        }
        Estate.Group g = estate.groupOf(i);
        return new ObserveResult("found", estate.kind(g).attributes(), null, Estate.PROVIDER, "get", null, Estate.REGION, estate.environment(g), at, 1L,
                null, wfrn);
    }

    private <T> Optional<Mono<T>> refuse() {
        Optional<Duration> wait = limit.acquire(Estate.PROVIDER, Estate.REGION, null, clock.instant());
        if (wait.isEmpty()) {
            return Optional.empty();
        }
        calls.refused(clock.instant());
        int seconds = (int) Math.max(1, (wait.get().toMillis() + 999) / 1000);
        return Optional.of(Mono.delay(Duration.ofMillis(20), time).then(Mono.error(new PlatformException(null, 429, null, seconds, null))));
    }

    private <T> Mono<T> answer(T value, Duration latency) {
        return latency.isZero() ? Mono.just(value) : Mono.delay(latency, time).thenReturn(value);
    }
}
