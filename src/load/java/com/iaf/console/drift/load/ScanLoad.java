package com.iaf.console.drift.load;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.drift.drift.adapters.memory.InMemoryFindingStore;
import com.iaf.console.drift.drift.adapters.memory.InMemoryWalkStore;
import com.iaf.console.drift.drift.adapters.temporal.LoopSpec;
import com.iaf.console.drift.drift.adapters.temporal.TemporalLoops;
import com.iaf.console.drift.drift.application.DriftMetrics;
import com.iaf.console.drift.drift.application.FindingEvents;
import com.iaf.console.drift.drift.application.Reconciler;
import com.iaf.console.drift.drift.application.ScanReport;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.ObserveBudget;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.kit.outbox.InMemoryOutbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * The scan load test: the service's own shard scanner, reconciler, observe budget and tier rules
 * over the synthetic estate's watch list and platform, for {@code load.hours} of shard loops on a
 * virtual clock. The loops are the {@code temporal} profile's (one per shard and tier, waking as
 * {@link TemporalLoops#specs} says, each turn cut after 14 minutes as the activity is), or with
 * {@code load.loops=in-process} the single loop of every shard every 30 seconds. They run
 * concurrently on the virtual clock, the platform's latency and the budget's waits included, so
 * hours take minutes and the same settings give the same numbers. Prints one row per hour.
 */
public final class ScanLoad {

    static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    static final Duration TICK_LIMIT = Duration.ofMinutes(14);

    private ScanLoad() {
    }

    public static void main(String[] args) {
        int hours = Integer.getInteger("load.hours", 3);
        int shards = Integer.getInteger("load.shards", 64);
        String loops = System.getProperty("load.loops", "temporal");
        Duration pageLatency = Duration.parse(System.getProperty("load.pageLatency", "PT0.5S"));
        Duration batchLatency = Duration.parse(System.getProperty("load.batchLatency", "PT0.3S"));
        int perMinute = Integer.getInteger("load.observePerMinute", 300);
        int burst = Integer.getInteger("load.burst", 50);
        double accountShare = Double.parseDouble(System.getProperty("load.accountShare", "0.25"));
        int platformPerMinute = Integer.getInteger("load.platformPerMinute", perMinute);
        ShardScanner.Settings settings = new ShardScanner.Settings(shards, Integer.getInteger("load.pageSize", 500), Integer.getInteger("load.batchSize", 100),
                Integer.getInteger("load.listThreshold", 20), Integer.getInteger("load.maxPerScan", 5000), Duration.ofMinutes(5));

        long wallStart = System.nanoTime();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long cpuStart = threads.getCurrentThreadCpuTime();
        VirtualTimeScheduler time = VirtualTimeScheduler.create();
        Clock clock = new VirtualClock(START, time);
        Estate estate = Estate.fromProperties(START);
        TierRules tiers = TierRules.defaults();
        ReadStats reads = new ReadStats();
        EstateWatches watches = new EstateWatches(estate, tiers, shards, reads);
        Map<String, String> digests = new HashMap<>();
        Map<String, TypeInfo> types = new HashMap<>();
        for (Estate.Kind k : Estate.KINDS) {
            digests.put(k.type(), Observation.found(Json.node(k.attributes()), START, Estate.PROVIDER, k.type() + ".get").digest());
            types.put(k.type(), new TypeInfo(k.type(), k.type() + "s", k.service(), Estate.PROVIDER, k.type() + ".get", Map.of(), Set.of(), null, "missing",
                    true, true, Set.of(), List.copyOf(k.attributes().keySet())));
        }
        watches.seed(digests);
        System.out.printf(Locale.ROOT, "estate: %,d resources in %,d accounts (the largest %,d), %,d groups; seeded in %.1f s%n", estate.size(),
                estate.accounts(), estate.groups().stream().filter(g -> g.account() == 0).mapToLong(Estate.Group::size).sum(), estate.groups().size(),
                (System.nanoTime() - wallStart) / 1e9);
        long[][] tierCounts = watches.staleness(START);
        System.out.printf(Locale.ROOT, "tiers: hot %,d, warm %,d, cold %,d; imported %s; loops %s; budget %d/min, burst %d, account share %.2f; latency page %s, batch %s%n",
                tierCounts[0][0], tierCounts[1][0], tierCounts[2][0], System.getProperty("load.imported", "0.001"), loops, perMinute, burst, accountShare,
                pageLatency, batchLatency);

        PlatformCalls calls = new PlatformCalls(START, hours, estate.groups().size());
        EstatePlatform platform = new EstatePlatform(estate, time, clock, pageLatency, batchLatency, new ObserveBudget(platformPerMinute, burst, accountShare), calls);
        AtomicLong stateCalls = new AtomicLong();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DriftMetrics metrics = new DriftMetrics(registry);
        Reconciler reconciler = new Reconciler(new InMemoryFindingStore(), watches, state(stateCalls), new FindingEvents(new InMemoryOutbox(), clock), tiers,
                Reconciler.Timing.DEFAULT, metrics, clock);
        ObserveBudget budget = new ObserveBudget(perMinute, burst, accountShare);
        ShardScanner scanner = new ShardScanner(watches, new InMemoryWalkStore(), typesPort(types), platform.port(), reconciler, budget, tiers, settings, metrics,
                clock, time);

        Turns turns = new Turns();
        if ("in-process".equals(loops)) {
            loop(time, turns, Duration.ofSeconds(30), true, () -> scanner.scanAll(null));
        } else {
            for (LoopSpec spec : TemporalLoops.specs(Estate.REGION, shards, tiers, Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(5), 96)) {
                if (LoopSpec.SCAN.equals(spec.kind())) {
                    loop(time, turns, Duration.ofMillis(spec.intervalMillis()), false, () -> scanner.scan(spec.shard(), Tier.of(spec.tier())));
                }
            }
        }

        Report report = new Report(estate, calls, reads, watches, registry, turns, stateCalls);
        report.header();
        long hourWall = System.nanoTime();
        long hourCpu = threads.getCurrentThreadCpuTime();
        for (int hour = 0; hour < hours; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                time.advanceTimeBy(Duration.ofMinutes(1));
            }
            long wall = System.nanoTime();
            long cpu = threads.getCurrentThreadCpuTime();
            report.hour(hour, clock.instant(), (wall - hourWall) / 1e9, (cpu - hourCpu) / 1e9);
            hourWall = wall;
            hourCpu = cpu;
        }
        report.walks(hours);
        System.out.printf(Locale.ROOT, "run: %.1f s wall, %.1f s CPU; open findings at the end %d (no provider drifts, so any is a wrong read)%n",
                (System.nanoTime() - wallStart) / 1e9, (threads.getCurrentThreadCpuTime() - cpuStart) / 1e9, watches.openFindings());
        time.dispose();
    }

    /** A loop: a turn, then its sleep, again and again; a Temporal turn is cut at the activity's limit. */
    private static void loop(VirtualTimeScheduler time, Turns turns, Duration sleep, boolean inProcess, java.util.function.Supplier<Mono<ScanReport>> tick) {
        Mono<Void> turn = Mono.defer(tick)
                .timeout(TICK_LIMIT, time)
                .doOnNext(turns::add)
                .onErrorResume(e -> {
                    turns.failed(e);
                    return Mono.empty();
                })
                .then(Mono.delay(sleep, time))
                .then();
        // the in-process loop is a fixed-rate interval that drops ticks while one runs; a Temporal loop sleeps after its turn
        if (inProcess) {
            Flux.interval(sleep, sleep, time).onBackpressureDrop().concatMap(n -> turn, 1).subscribe();
        } else {
            turn.repeat().subscribe();
        }
    }

    private static StateServicePort state(AtomicLong calls) {
        return (StateServicePort) Proxy.newProxyInstance(StateServicePort.class.getClassLoader(), new Class<?>[] {StateServicePort.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "recordObservation", "recordDrift", "resolveDrift" -> {
                        calls.incrementAndGet();
                        yield Mono.empty();
                    }
                    case "toString" -> "the load estate's state service";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName() + " is not part of a scan");
                });
    }

    private static ResourceTypesPort typesPort(Map<String, TypeInfo> types) {
        return new ResourceTypesPort() {
            @Override
            public Mono<TypeInfo> find(String name) {
                return Mono.justOrEmpty(types.get(name));
            }

            @Override
            public Flux<TypeInfo> list() {
                return Flux.fromIterable(types.values());
            }
        };
    }

    /** The virtual clock: the scheduler's time from the run's start. */
    static final class VirtualClock extends Clock {
        private final Instant start;
        private final VirtualTimeScheduler time;

        VirtualClock(Instant start, VirtualTimeScheduler time) {
            this.start = start;
            this.time = time;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return start.plusNanos(time.now(TimeUnit.NANOSECONDS));
        }
    }

    /** What the turns answered. */
    static final class Turns {
        long count;
        long checked;
        long deferred;
        long errors;
        long cut;
        long failed;

        void add(ScanReport r) {
            count++;
            checked += r.checked();
            deferred += r.deferred();
            errors += r.errors();
        }

        void failed(Throwable e) {
            count++;
            if (e instanceof java.util.concurrent.TimeoutException) {
                cut++;
            } else {
                failed++;
                if (failed <= 3) {
                    System.out.println("turn failed: " + e);
                }
            }
        }

        Turns copy() {
            Turns t = new Turns();
            t.count = count;
            t.checked = checked;
            t.deferred = deferred;
            t.errors = errors;
            t.cut = cut;
            t.failed = failed;
            return t;
        }
    }

    /** The rows: per hour what the platform and the provider saw, how timely the reads were, and the walks. */
    static final class Report {
        private final Estate estate;
        private final PlatformCalls calls;
        private final ReadStats reads;
        private final EstateWatches watches;
        private final SimpleMeterRegistry registry;
        private final Turns turns;
        private final AtomicLong stateCalls;
        private ReadStats lastReads = new ReadStats();
        private Turns lastTurns = new Turns();
        private Map<String, Double> lastDeferred = new HashMap<>();
        private long lastState;
        private final List<String> rows = new ArrayList<>();

        Report(Estate estate, PlatformCalls calls, ReadStats reads, EstateWatches watches, SimpleMeterRegistry registry, Turns turns, AtomicLong stateCalls) {
            this.estate = estate;
            this.calls = calls;
            this.reads = reads;
            this.watches = watches;
            this.registry = registry;
            this.turns = turns;
            this.stateCalls = stateCalls;
        }

        void header() {
            System.out.println();
            System.out.println("| Hour | Requests/s mean | peak min | Provider calls/s mean | peak min | Pages | Batches (resources) | Discover pages | 429 | Deferred: budget, 429 | Reads/resource hot, warm, cold | Early reads | Overdue reads | Past interval at end | Turns cut | State calls | Wall s | CPU s |");
            System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        }

        void hour(int hour, Instant now, double wall, double cpu) {
            long requests = 0;
            long provider = 0;
            long peakRequests = 0;
            long peakProvider = 0;
            long pages = 0;
            long batches = 0;
            long batchItems = 0;
            long discover = 0;
            long refused = 0;
            for (int m = hour * 60; m < hour * 60 + 60; m++) {
                requests += calls.requests(m);
                provider += calls.providerCalls(m);
                peakRequests = Math.max(peakRequests, calls.requests(m));
                peakProvider = Math.max(peakProvider, calls.providerCalls(m));
                pages += calls.count(m, PlatformCalls.PAGES);
                batches += calls.count(m, PlatformCalls.BATCHES);
                batchItems += calls.count(m, PlatformCalls.BATCH_ITEMS);
                discover += calls.count(m, PlatformCalls.DISCOVER_PAGES);
                refused += calls.count(m, PlatformCalls.REFUSED);
            }
            ReadStats r = reads.since(lastReads);
            lastReads = reads.copy();
            long[][] stale = watches.staleness(now);
            long total = 0;
            long early = 0;
            long overdue = 0;
            StringBuilder perResource = new StringBuilder();
            for (Tier t : Tier.values()) {
                total += r.reads(t);
                early += r.count(t, ReadStats.EARLY);
                overdue += r.count(t, ReadStats.OVERDUE);
                perResource.append(perResource.isEmpty() ? "" : ", ")
                        .append(String.format(Locale.ROOT, "%.2f", stale[t.ordinal()][0] == 0 ? 0 : (double) r.reads(t) / stale[t.ordinal()][0]));
            }
            long staleNow = stale[0][1] + stale[1][1] + stale[2][1];
            double budgetDeferred = deferred("budget");
            double limitDeferred = deferred("rate-limited");
            Turns t = turns.copy();
            long state = stateCalls.get();
            String row = String.format(Locale.ROOT, "| %d | %.2f | %.2f | %.1f | %.1f | %,d | %,d (%,d) | %,d | %,d | %,.0f, %,.0f | %s | %.1f%% | %.2f%% | %.2f%% | %d | %,d | %.0f | %.0f |",
                    hour + 1, requests / 3600.0, peakRequests / 60.0, provider / 3600.0, peakProvider / 60.0, pages, batches, batchItems, discover, refused,
                    budgetDeferred, limitDeferred, perResource, pct(early, total), pct(overdue, total), pct(staleNow, estate.size()), t.cut - lastTurns.cut,
                    state - lastState, wall, cpu);
            lastTurns = t;
            lastState = state;
            rows.add(row);
            System.out.println(row);
            if (hour == 0 || r.worstGapMillis(Tier.WARM) > 0) {
                System.out.printf(Locale.ROOT, "  past interval at end by tier: hot %,d, warm %,d, cold %,d; longest gap between reads: hot %s, warm %s, cold %s%n",
                        stale[0][1], stale[1][1], stale[2][1], Duration.ofMillis(r.worstGapMillis(Tier.HOT)), Duration.ofMillis(r.worstGapMillis(Tier.WARM)),
                        Duration.ofMillis(r.worstGapMillis(Tier.COLD)));
            }
        }

        private double deferred(String reason) {
            Counter c = registry.find("console_drift_deferred_total").tag("reason", reason).counter();
            double now = c == null ? 0 : c.count();
            double before = lastDeferred.getOrDefault(reason, 0.0);
            lastDeferred.put(reason, now);
            return now - before;
        }

        /** Per hour, the walks of each account's list of a type in an environment: how many lists were walked, how often, and the largest. */
        void walks(int hours) {
            System.out.println();
            System.out.println("| Hour | Lists walked | Walks started | Walks finished | Started per walked list, mean / max | Largest list (resources): started, finished |");
            System.out.println("|---|---|---|---|---|---|");
            Estate.Group largest = estate.groups().stream().max((a, b) -> Long.compare(a.size(), b.size())).orElseThrow();
            for (int hour = 0; hour < hours; hour++) {
                int[] started = calls.walksStarted(hour);
                int[] finished = calls.walksFinished(hour);
                long lists = 0;
                long s = 0;
                long f = 0;
                int max = 0;
                for (int g = 0; g < started.length; g++) {
                    if (started[g] > 0) {
                        lists++;
                    }
                    s += started[g];
                    f += finished[g];
                    max = Math.max(max, started[g]);
                }
                System.out.printf(Locale.ROOT, "| %d | %,d of %,d | %,d | %,d | %.2f / %d | %,d: %d, %d |%n", hour + 1, lists, started.length, s, f,
                        lists == 0 ? 0 : (double) s / lists, max, largest.size(), started[largest.index()], finished[largest.index()]);
            }
            if (!calls.discoverWalks().isEmpty()) {
                StringBuilder b = new StringBuilder("discover walks (type|environment: started, finished):");
                calls.discoverWalks().forEach((k, v) -> b.append(' ').append(k).append(": ").append(v[0]).append(", ").append(v[1]).append(';'));
                System.out.println(b);
            }
        }

        private static double pct(long part, long whole) {
            return whole == 0 ? 0 : 100.0 * part / whole;
        }
    }
}
