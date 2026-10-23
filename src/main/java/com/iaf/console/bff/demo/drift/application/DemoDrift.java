package com.iaf.console.bff.demo.drift.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.directory.domain.DemoAccount;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.drift.domain.Alarm;
import com.iaf.console.bff.drift.domain.DiscoveryRun;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.drift.domain.FindingStats;
import com.iaf.console.bff.drift.domain.FindingsSummary;
import com.iaf.console.bff.drift.domain.Money;
import com.iaf.console.bff.drift.domain.Recommendation;
import com.iaf.console.bff.drift.domain.RecommendationsSummary;
import com.iaf.console.bff.drift.domain.ResourceMetrics;
import com.iaf.console.bff.drift.domain.UnmanagedObject;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The drift service, in memory, for the demo: a few findings on the demo's resources, two objects
 * no account manages (with their owner guesses and monthly costs), made-up metrics for virtual
 * machines with alarms over them, and two rightsizing recommendations, one idle and one oversized.
 * Small and deterministic: every seed is named and its times are relative to when its account is
 * first asked about; a revert's job "runs" for {@link #REVERT_TAKES} and the next read then finds
 * the finding closed as reverted; a discovery run ends at its first read. It keeps the demo state
 * stand-in in step as the drift service keeps the state service: a finding raises and clears the
 * resource's drift flag, an import mints the resource. No platform job runs: the job ids are the
 * stand-in's own.
 */
public class DemoDrift {

    /** How long a revert's job takes before the next read finds the resource back as it should be. */
    public static final Duration REVERT_TAKES = Duration.ofSeconds(5);
    /** The series a virtual machine's made-up metrics read gives, all in percent. */
    public static final List<String> SERIES = List.of("cpuPercent", "memoryPercent", "diskPercent");
    static final String BASIS = "console list prices (demo)";
    static final int MAX_POINTS = 2000;
    private static final String METRICS_TYPE = "vm";
    private static final Pattern ALARM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final DemoStateStore store;
    private final DemoDirectory directory;
    private final Trust trust;
    private final String platformAudience;
    private final Clock clock;

    private final Map<String, FindingRow> findings = new LinkedHashMap<>();
    private final Map<String, ObjectRow> objects = new LinkedHashMap<>();
    private final Map<String, RunRow> runs = new LinkedHashMap<>();
    private final Map<String, AlarmRow> alarms = new LinkedHashMap<>();
    private final Map<String, RecommendationRow> recommendations = new LinkedHashMap<>();
    private final Map<String, Object> replays = new HashMap<>();
    private final Map<String, String> onBehalfOf = new ConcurrentHashMap<>();
    private final Set<String> seeded = new HashSet<>();
    private final Map<String, Instant> seededAt = new HashMap<>();
    private final AtomicLong jobs = new AtomicLong();
    private final AtomicLong runIds = new AtomicLong();

    public DemoDrift(DemoStateStore store, DemoDirectory directory, Trust trust, String platformAudience, Clock clock) {
        this.store = store;
        this.directory = directory;
        this.trust = trust;
        this.platformAudience = platformAudience;
        this.clock = clock;
    }

    // ---- findings

    /** A page of an account's findings in a region, open ones first, the newest first. */
    public synchronized Page<Finding> findings(String accountId, String region, String status, String severity, String type, String wfrn,
            Instant openedAfter, int limit, String after) {
        seed(accountId);
        settleAll();
        String wanted = status == null ? "open" : status;
        List<Finding> rows = findings.values().stream()
                .filter(f -> f.accountId.equals(accountId) && (region == null || region.equals(f.region)))
                .filter(f -> "all".equals(wanted) || wanted.equals(f.status))
                .filter(f -> severity == null || severity.equals(f.severity))
                .filter(f -> type == null || type.equals(f.type))
                .filter(f -> wfrn == null || (wfrn.contains("*") ? Wfrn.matches(wfrn, f.wfrn) : wfrn.equals(f.wfrn)))
                .filter(f -> openedAfter == null || f.openedAt.isAfter(openedAfter))
                .sorted(Comparator.comparing((FindingRow f) -> !f.open()).thenComparing(f -> f.openedAt, Comparator.reverseOrder()))
                .map(FindingRow::view)
                .toList();
        return page(rows, limit, after);
    }

    public synchronized FindingsSummary summary(String accountId, String region) {
        seed(accountId);
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        int open = 0;
        settleAll();
        for (FindingRow f : findings.values()) {
            if (f.accountId.equals(accountId) && (region == null || region.equals(f.region)) && f.open()) {
                open++;
                byType.merge(f.type, 1, Integer::sum);
                bySeverity.merge(f.severity, 1, Integer::sum);
            }
        }
        return new FindingsSummary(open, byType, bySeverity, lastScan(accountId));
    }

    /**
     * Findings opened and closed in a window, for an account or (the console-internal principal
     * naming none) for every account: the last 30 days until now by default; 400 when the window
     * ends before it starts; no mean time to close when nothing closed in it.
     */
    public synchronized FindingStats stats(String accountId, Instant since, Instant until) {
        Instant end = until == null ? clock.instant() : until;
        Instant start = since == null ? end.minus(Duration.ofDays(30)) : since;
        if (!start.isBefore(end)) {
            throw invalid("range", "/since", "since comes before until");
        }
        if (accountId == null) {
            directory.accounts().forEach(a -> seed(a.accountId()));
        } else {
            seed(accountId);
        }
        int opened = 0;
        int open = 0;
        int reverted = 0;
        int accepted = 0;
        int ignored = 0;
        int vanished = 0;
        long minutes = 0;
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        settleAll();
        for (FindingRow f : findings.values()) {
            if (accountId != null && !accountId.equals(f.accountId)) {
                continue;
            }
            if (!f.openedAt.isBefore(start) && f.openedAt.isBefore(end)) {
                opened++;
                bySeverity.merge(f.severity, 1, Integer::sum);
            }
            if (f.open()) {
                open++;
            }
            if (f.closedAt != null && !f.closedAt.isBefore(start) && f.closedAt.isBefore(end)) {
                switch (f.resolution) {
                    case "reverted" -> reverted++;
                    case "accepted" -> accepted++;
                    case "ignored" -> ignored++;
                    default -> vanished++;
                }
                minutes += Duration.between(f.openedAt, f.closedAt).toMinutes();
            }
        }
        int closed = reverted + accepted + ignored + vanished;
        Double mean = closed == 0 ? null : Math.round(minutes * 10.0 / closed) / 10.0;
        return new FindingStats(accountId, start, end, opened, new FindingStats.Closed(closed, reverted, accepted, ignored, vanished), open, bySeverity,
                mean);
    }

    public synchronized Finding finding(String accountId, String findingId) {
        return owned(accountId, findingId).view();
    }

    /**
     * Starts the revert's job for the person the assertion names: 409 {@code finding.closed},
     * {@code finding.reverting}, {@code finding.notRevertable}, or {@code revert.needsPerson}
     * without a valid {@code X-On-Behalf-Of}.
     */
    public synchronized DriftResults.RevertStarted revert(String accountId, String findingId, String reason, String actor, String assertion,
            String key) {
        Object replay = replay("revert", accountId, key);
        if (replay != null) {
            return (DriftResults.RevertStarted) replay;
        }
        FindingRow f = owned(accountId, findingId);
        refuseClosed(f);
        if ("running".equals(f.revertStatus)) {
            throw new ConflictException("finding.reverting: a revert job is already running", Map.of("jobId", f.revertJobId));
        }
        if (f.refusal != null) {
            throw new ConflictException("finding.notRevertable: " + f.refusal, Map.of("type", f.type));
        }
        String person = person(accountId, assertion);
        onBehalfOf.put(findingId, person);
        f.revertJobId = "job-drift-" + jobs.incrementAndGet();
        f.revertStatus = "running";
        f.revertStartedAt = clock.instant();
        f.decidedBy = actor == null ? person : actor;
        f.reason = reason;
        f.version++;
        return remember("revert", accountId, key, new DriftResults.RevertStarted(findingId, f.revertJobId));
    }

    /** Makes the observed values the desired ones: the finding closes as accepted, the resource takes the provider's values. */
    public synchronized Finding accept(String accountId, String findingId, String reason, String actor, String key) {
        Object replay = replay("accept", accountId, key);
        if (replay != null) {
            return (Finding) replay;
        }
        FindingRow f = owned(accountId, findingId);
        refuseClosed(f);
        if ("running".equals(f.revertStatus)) {
            throw new ConflictException("finding.reverting: a revert job is running; wait for it or let it fail", Map.of("jobId", f.revertJobId));
        }
        close(f, "accepted", actor, reason);
        return remember("accept", accountId, key, f.view());
    }

    /** Silences the drifted fields until the desired state changes, or until {@code until}: the finding closes as ignored. */
    public synchronized Finding ignore(String accountId, String findingId, String reason, String actor, Instant until, String key) {
        Object replay = replay("ignore", accountId, key);
        if (replay != null) {
            return (Finding) replay;
        }
        FindingRow f = owned(accountId, findingId);
        refuseClosed(f);
        f.ignoredUntil = until;
        f.fields = f.fields.stream().map(field -> new Finding.Field(field.path(), field.expected(), field.observed(), true)).toList();
        close(f, "ignored", actor, reason);
        return remember("ignore", accountId, key, f.view());
    }

    /** The verified subject of the last {@code X-On-Behalf-Of} a revert or an apply of this id carried, for the tests. */
    public Optional<String> onBehalfOf(String findingOrRecommendationId) {
        return Optional.ofNullable(onBehalfOf.get(findingOrRecommendationId));
    }

    // ---- the shadow-infrastructure finder

    /**
     * Starts a scan of a type's provider, one run per type and region at a time: while one runs,
     * a second start is a 409 {@code discover.running} naming it, as the drift service does. Only
     * virtual machines have a discover read here; another type is 400 {@code type.notDiscoverable}.
     */
    public synchronized DiscoveryRun discover(String accountId, String region, String type, String key) {
        Object replay = replay("discover", accountId, key);
        if (replay != null) {
            return (DiscoveryRun) replay;
        }
        if (type == null || type.isBlank()) {
            throw invalid("required", "/type", "type is required");
        }
        if (!METRICS_TYPE.equals(type)) {
            throw invalid("type.notDiscoverable", "/type", type + " has no observe.discover or observe.list action");
        }
        seed(accountId);
        for (RunRow running : runs.values()) {
            if (running.accountId.equals(accountId) && running.type.equals(type) && running.region.equals(region)
                    && DiscoveryRun.RUNNING.equals(running.status)) {
                throw new ConflictException("discover.running: a discovery run of " + type + " in " + region + " is already running",
                        Map.of("runId", running.runId));
            }
        }
        RunRow run = new RunRow();
        run.runId = "disc-" + runIds.incrementAndGet();
        run.accountId = accountId;
        run.type = type;
        run.region = region;
        run.status = DiscoveryRun.RUNNING;
        run.startedAt = clock.instant();
        runs.put(run.runId, run);
        return remember("discover", accountId, key, run.view());
    }

    /** A run's progress: it ends at its first read, having seen the account's managed machines and the objects no account manages. */
    public synchronized DiscoveryRun run(String accountId, String runId) {
        RunRow run = runs.get(runId);
        if (run == null || !run.accountId.equals(accountId)) {
            throw NotFoundException.of("discovery run", runId);
        }
        if (DiscoveryRun.RUNNING.equals(run.status)) {
            long managed = store.snapshot(accountId, run.region).stream()
                    .filter(r -> run.type.equals(r.type()) && !StateResource.DELETED.equals(r.state())).count();
            long unmanaged = objects.values().stream().filter(o -> o.visibleTo(accountId) && o.type.equals(run.type) && o.region.equals(run.region)).count();
            run.status = DiscoveryRun.SUCCEEDED;
            run.endedAt = clock.instant();
            run.seen = (int) (managed + unmanaged);
            run.unmanaged = (int) unmanaged;
        }
        return run.view();
    }

    /** A page of the objects an account's runs found and the ones another account handed to it; {@code owner} any, mine or unknown. */
    public synchronized Page<UnmanagedObject> unmanaged(String accountId, String region, String type, String owner, int limit, String after) {
        seed(accountId);
        String whose = owner == null ? "any" : owner;
        Predicate<ObjectRow> owned = switch (whose) {
            case "mine" -> o -> (o.guess != null && accountId.equals(o.guess.accountId())) || (o.assignment != null && accountId.equals(o.assignment.accountId()));
            case "unknown" -> o -> o.guess == null;
            default -> o -> true;
        };
        List<UnmanagedObject> rows = objects.values().stream()
                .filter(o -> o.visibleTo(accountId) && (region == null || region.equals(o.region)) && (type == null || type.equals(o.type)))
                .filter(owned)
                .sorted(Comparator.comparing((ObjectRow o) -> o.name))
                .map(ObjectRow::view)
                .toList();
        return page(rows, limit, after);
    }

    /** Takes an object under management: the demo state stand-in mints it, and it leaves every account's list. */
    public Mono<DriftResults.Imported> importObject(String accountId, String region, String type, String providerId, String name, String actor,
            String key, String requestId) {
        ObjectRow row;
        synchronized (this) {
            Object replay = replay("import", accountId, key);
            if (replay != null) {
                return Mono.just((DriftResults.Imported) replay);
            }
            seed(accountId);
            row = visibleObject(accountId, region, type, providerId);
        }
        String stateKey = key == null || key.isBlank() ? null : "drift-import:" + accountId + ":" + key;
        return store.importDiscovered(accountId, region, type, providerId, name == null || name.isBlank() ? row.name : name, row.observed, actor,
                stateKey, requestId).map(answer -> {
                    synchronized (this) {
                        row.imported = true;
                        return remember("import", accountId, key, new DriftResults.Imported(answer.resource().wfrn()));
                    }
                });
    }

    /** Hands an object to another team's account: 400 {@code unmanaged.unknownAccount} when no demo account has the id, or it is this one. */
    public synchronized UnmanagedObject assign(String accountId, String region, String type, String providerId, String toAccount, String note,
            String actor, String key) {
        Object replay = replay("assign", accountId, key);
        if (replay != null) {
            return (UnmanagedObject) replay;
        }
        seed(accountId);
        ObjectRow row = visibleObject(accountId, region, type, providerId);
        if (toAccount == null || toAccount.equals(accountId) || directory.account(toAccount).isEmpty()) {
            throw invalid("unmanaged.unknownAccount", "/accountId", "unmanaged.unknownAccount: no active account has the id " + toAccount + ", or it is this one");
        }
        row.assignment = new UnmanagedObject.Assignment(toAccount, accountName(toAccount), accountId, actor, note, clock.instant());
        return remember("assign", accountId, key, row.view());
    }

    // ---- monitoring

    /**
     * A virtual machine's made-up series over a range: the last 24 hours by default, one point per
     * 5 minutes for a day or less, else per hour; 404 {@code metrics.notSupported} for any other type.
     */
    public synchronized ResourceMetrics metrics(String accountId, String wfrn, Instant from, Instant to, Duration step) {
        StateResource r = resource(accountId, wfrn);
        if (!METRICS_TYPE.equals(r.type())) {
            throw new NotFoundException("metrics.notSupported: the type " + r.type() + " has no metrics read");
        }
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(24)) : from;
        if (!start.isBefore(end)) {
            throw invalid("range", "/from", "from comes before to");
        }
        Duration every = step != null ? step : Duration.between(start, end).compareTo(Duration.ofDays(1)) <= 0 ? Duration.ofMinutes(5) : Duration.ofHours(1);
        if (every.compareTo(Duration.ofMinutes(1)) < 0) {
            throw invalid("range", "/step", "step is at least PT1M");
        }
        long seconds = every.getSeconds();
        long count = Duration.between(start, end).getSeconds() / seconds + 1;
        if (count > MAX_POINTS) {
            throw invalid("range", "/step", "the range holds " + count + " points at that step, more than " + MAX_POINTS + ": take a longer step");
        }
        Profile profile = Profile.of(r.name());
        List<ResourceMetrics.Series> series = new ArrayList<>();
        for (String name : SERIES) {
            List<ResourceMetrics.Point> points = new ArrayList<>();
            for (long t = ceil(start.getEpochSecond(), seconds); t <= end.getEpochSecond(); t += seconds) {
                points.add(new ResourceMetrics.Point(Instant.ofEpochSecond(t), value(wfrn, name, profile, t / 60)));
            }
            series.add(new ResourceMetrics.Series(name, "percent", points));
        }
        return new ResourceMetrics(wfrn, start, end, every.toString(), series, "vsphere", "vm.metrics");
    }

    /** A page of an account's alarms, of one resource when {@code wfrn} is set, firing ones first. */
    public synchronized Page<Alarm> alarms(String accountId, String wfrn, String state, int limit, String after) {
        seed(accountId);
        Instant now = clock.instant();
        alarms.values().stream().filter(a -> a.accountId.equals(accountId)).forEach(a -> evaluate(a, now));
        List<Alarm> rows = alarms.values().stream()
                .filter(a -> a.accountId.equals(accountId) && (wfrn == null || wfrn.equals(a.wfrn)))
                .filter(a -> state == null || state.equals(a.state))
                .sorted(Comparator.comparing((AlarmRow a) -> !Alarm.ALARM.equals(a.state)).thenComparing(a -> a.alarmId))
                .map(AlarmRow::view)
                .toList();
        return page(rows, limit, after);
    }

    public synchronized Alarm alarm(String accountId, String alarmId) {
        seed(accountId);
        AlarmRow a = alarms.get(accountId + "|" + alarmId);
        if (a == null) {
            throw NotFoundException.of("alarm", alarmId);
        }
        evaluate(a, clock.instant());
        return a.view();
    }

    /** Creates or replaces an alarm under the caller's id; a change keeps the alarm's state until the next minute's evaluation. */
    public synchronized DriftResults.AlarmSaved putAlarm(String accountId, String alarmId, JsonNode body) {
        if (alarmId == null || !ALARM_ID.matcher(alarmId).matches()) {
            throw invalid("format", "/alarmId", "an alarm id is 1 to 64 letters, digits, '_' or '-'");
        }
        seed(accountId);
        String wfrn = required(body, "wfrn");
        StateResource r = resource(accountId, wfrn);
        if (!METRICS_TYPE.equals(r.type())) {
            throw new NotFoundException("metrics.notSupported: the type " + r.type() + " has no metrics read");
        }
        String metric = required(body, "metric");
        if (!SERIES.contains(metric)) {
            throw invalid("metric.unknown", "/metric", "the type " + r.type() + " reads " + String.join(", ", SERIES));
        }
        String comparison = required(body, "comparison");
        if (!comparison.equals("above") && !comparison.equals("below")) {
            throw invalid("enum", "/comparison", "comparison is above or below");
        }
        if (!body.path("threshold").isNumber()) {
            throw invalid("required", "/threshold", "threshold is a number");
        }
        JsonNode windowMinutes = body.path("windowMinutes");
        int window = windowMinutes.isMissingNode() || windowMinutes.isNull() ? 10
                : windowMinutes.isIntegralNumber() && windowMinutes.canConvertToInt() ? windowMinutes.intValue() : -1;
        if (window < 1 || window > 1440) {
            throw invalid("range", "/windowMinutes", "windowMinutes is 1 to 1440");
        }
        String name = body.hasNonNull("name") ? body.path("name").asText() : null;
        if (name != null && name.length() > 128) {
            throw invalid("length", "/name", "name is at most 128 characters");
        }
        Instant now = clock.instant();
        String key = accountId + "|" + alarmId;
        AlarmRow a = alarms.get(key);
        boolean created = a == null;
        if (created) {
            a = new AlarmRow();
            a.alarmId = alarmId;
            a.accountId = accountId;
            a.createdBy = body.hasNonNull("actor") ? body.path("actor").asText() : null;
            a.createdAt = now;
            a.state = Alarm.INSUFFICIENT;
            a.stateSince = now;
            alarms.put(key, a);
        }
        a.wfrn = wfrn;
        a.type = r.type();
        a.name = name;
        a.metric = metric;
        a.comparison = comparison;
        a.threshold = body.path("threshold").asDouble();
        a.windowMinutes = window;
        a.updatedAt = now;
        if (created) {
            evaluate(a, now);
        }
        return new DriftResults.AlarmSaved(a.view(), created);
    }

    public synchronized void deleteAlarm(String accountId, String alarmId) {
        if (alarms.remove(accountId + "|" + alarmId) == null) {
            throw NotFoundException.of("alarm", alarmId);
        }
    }

    // ---- rightsizing

    /** A page of an account's recommendations, open ones by default, the largest saving first. */
    public synchronized Page<Recommendation> recommendations(String accountId, String type, String kind, String status, String wfrn, int limit,
            String after) {
        seed(accountId);
        String wanted = status == null ? "open" : status;
        List<Recommendation> rows = recommendations.values().stream()
                .filter(r -> r.accountId.equals(accountId) && wanted.equals(r.status))
                .filter(r -> (type == null || type.equals(r.type)) && (kind == null || kind.equals(r.kind)) && (wfrn == null || wfrn.equals(r.wfrn)))
                .sorted(Comparator.comparing((RecommendationRow r) -> r.monthlySaving.amount(), Comparator.reverseOrder()).thenComparing(r -> r.name))
                .map(RecommendationRow::view)
                .toList();
        return page(rows, limit, after);
    }

    public synchronized RecommendationsSummary recommendationsSummary(String accountId) {
        seed(accountId);
        Map<String, Integer> byKind = new LinkedHashMap<>();
        BigDecimal saving = BigDecimal.ZERO;
        int open = 0;
        for (RecommendationRow r : recommendations.values()) {
            if (r.accountId.equals(accountId) && r.open()) {
                open++;
                byKind.merge(r.kind, 1, Integer::sum);
                saving = saving.add(r.monthlySaving.amount());
            }
        }
        Instant pass = seededAt.get(accountId);
        return new RecommendationsSummary(open, byKind, new Money(saving, "USD", BASIS), pass == null ? null : pass.minus(Duration.ofHours(3)));
    }

    public synchronized Recommendation recommendation(String accountId, String recommendationId) {
        return ownedRecommendation(accountId, recommendationId).view();
    }

    /**
     * Starts the resize or delete job for the person the assertion names; the recommendation is
     * applied from then on. 409 {@code recommendation.closed}, {@code recommendation.changing}
     * (a change is in flight on the resource) or {@code revert.needsPerson}.
     */
    public synchronized DriftResults.ApplyStarted apply(String accountId, String recommendationId, String reason, String actor, String assertion,
            String key) {
        Object replay = replay("apply", accountId, key);
        if (replay != null) {
            return (DriftResults.ApplyStarted) replay;
        }
        RecommendationRow r = ownedRecommendation(accountId, recommendationId);
        if (!r.open()) {
            throw new ConflictException("recommendation.closed: the recommendation was " + r.status, Map.of("status", r.status));
        }
        StateResource resource = resource(accountId, r.wfrn);
        if (!StateResource.ACTIVE.equals(resource.state())) {
            throw new ConflictException("recommendation.changing: a change is in flight on the resource", Map.of("state", resource.state()));
        }
        String person = person(accountId, assertion);
        onBehalfOf.put(recommendationId, person);
        r.status = "applied";
        r.jobId = "job-drift-" + jobs.incrementAndGet();
        r.decidedBy = actor == null ? person : actor;
        r.reason = reason;
        r.updatedAt = clock.instant();
        return remember("apply", accountId, key, new DriftResults.ApplyStarted(recommendationId, r.jobId));
    }

    /** Sets a recommendation aside, for good or until {@code until}: 409 {@code recommendation.closed} once it is not open. */
    public synchronized Recommendation dismiss(String accountId, String recommendationId, String reason, String actor, Instant until, String key) {
        Object replay = replay("dismiss", accountId, key);
        if (replay != null) {
            return (Recommendation) replay;
        }
        RecommendationRow r = ownedRecommendation(accountId, recommendationId);
        if (!r.open()) {
            throw new ConflictException("recommendation.closed: the recommendation was " + r.status, Map.of("status", r.status));
        }
        r.status = "dismissed";
        r.dismissedUntil = until;
        r.decidedBy = actor;
        r.reason = reason;
        r.updatedAt = clock.instant();
        return remember("dismiss", accountId, key, r.view());
    }

    // ---- the seeds

    /**
     * What the demo shows for an account, each seed once, as soon as the resource it is about is
     * in the demo state stand-in (the platform's feed fills it at start).
     */
    private void seed(String accountId) {
        Instant now = clock.instant();
        seededAt.putIfAbsent(accountId, now);
        Instant t0 = seededAt.get(accountId);
        Map<String, StateResource> byName = new HashMap<>();
        for (StateResource r : store.snapshot(accountId, null)) {
            if (!StateResource.DELETED.equals(r.state()) && r.name() != null) {
                byName.put(r.type() + "/" + r.name(), r);
            }
        }
        if (DemoDirectory.ACCOUNT_COMPUTE.equals(accountId)) {
            findingSeed(byName.get("vm/web-01"), "fnd-web-01-cpu", "warning", "/cpu", NODES.numberNode(8), t0.minus(Duration.ofHours(2)), null);
            findingSeed(byName.get("vm/shop-01"), "fnd-shop-01-memory", "critical", "/memoryGb", NODES.numberNode(32), t0.minus(Duration.ofHours(26)),
                    null);
            findingSeed(byName.get("vm/api-01"), "fnd-api-01-environment", "info", "/environment", NODES.textNode("staging"),
                    t0.minus(Duration.ofHours(5)), "the type's update workflow cannot change environment");
            closedSeed(byName.get("vm/web-02"), "fnd-web-02-memory", "warning", "/memoryGb", NODES.numberNode(32), t0.minus(Duration.ofDays(4)),
                    t0.minus(Duration.ofDays(3)), "accepted", "user_dana", "Sized up for the sale; keep it");
            closedSeed(byName.get("vm/cache-01"), "fnd-cache-01-cpu", "warning", "/cpu", NODES.numberNode(2), t0.minus(Duration.ofDays(9)),
                    t0.minus(Duration.ofDays(9)).plus(Duration.ofMinutes(42)), "reverted", "user_mateo", null);
            objectSeed(accountId, "vm-5c2e81", "data-etl-02", 4, 16, Map.of("team", "team-data"),
                    new UnmanagedObject.OwnerGuess(DemoDirectory.ACCOUNT_DATA, accountName(DemoDirectory.ACCOUNT_DATA), "team-data", "high",
                            List.of("Tagged team=team-data")),
                    "96.36", t0.minus(Duration.ofDays(6)), t0.minus(Duration.ofHours(1)));
            objectSeed(accountId, "vm-9a41d7", "legacy-ftp", 2, 8, Map.of(),
                    new UnmanagedObject.OwnerGuess(accountId, accountName(accountId), "team-compute", "low",
                            List.of(accountName(accountId) + " is the only account with managed resources of this type here")),
                    "48.18", t0.minus(Duration.ofDays(20)), t0.minus(Duration.ofHours(1)));
            alarmSeed(byName.get("vm/web-01"), "web-01-cpu-high", "web-01 CPU above 30%", "cpuPercent", "above", 30, 10, "user_gonzalo", t0);
            alarmSeed(byName.get("vm/web-02"), "web-02-memory-high", "web-02 memory above 80%", "memoryPercent", "above", 80, 15, "user_dana", t0);
            recommendationSeed(byName.get("vm/batch-01"), "rec-batch-01-idle", "idle", "delete", null,
                    new Recommendation.Utilisation(t0.minus(Duration.ofDays(14)), t0, 4032, 3.1, 4.8, 10.2, 12.5), "48.18", "48.18", t0);
            recommendationSeed(byName.get("vm/web-02"), "rec-web-02-oversized", "oversized", "resize", Map.of("cpu", 2, "memoryGb", 8),
                    new Recommendation.Utilisation(t0.minus(Duration.ofDays(14)), t0, 4032, 12.4, 18.9, 21.7, 26.0), "96.36", "48.18", t0);
        } else if (DemoDirectory.ACCOUNT_DATA.equals(accountId)) {
            findingSeed(byName.get("database/orders-db"), "fnd-orders-db-tier", "warning", "/tier", NODES.textNode("M40"),
                    t0.minus(Duration.ofHours(7)), "the type database has no update workflow in the catalog");
        }
    }

    /** An open finding on a resource, its drift flag raised in the demo state stand-in. */
    private void findingSeed(StateResource r, String findingId, String severity, String path, JsonNode observed, Instant openedAt, String refusal) {
        if (r == null || !seeded.add(findingId)) {
            return;
        }
        FindingRow f = findingRow(r, findingId, severity, path, observed, openedAt);
        f.refusal = refusal;
        findings.put(findingId, f);
        try {
            store.recordDrift(r.accountId(), r.wfrn(), findingId, severity, fieldsJson(f.fields), openedAt);
        } catch (RuntimeException e) {
            // the resource went between the snapshot and now: the finding stays, as the drift service's would until its next scan
        }
    }

    /** A finding closed before the demo began, for the history and the executive numbers. */
    private void closedSeed(StateResource r, String findingId, String severity, String path, JsonNode observed, Instant openedAt, Instant closedAt,
            String resolution, String decidedBy, String reason) {
        if (r == null || !seeded.add(findingId)) {
            return;
        }
        FindingRow f = findingRow(r, findingId, severity, path, observed, openedAt);
        f.status = "closed";
        f.closedAt = closedAt;
        f.observedAt = closedAt;
        f.resolution = resolution;
        f.decidedBy = decidedBy;
        f.reason = reason;
        if ("reverted".equals(resolution)) {
            f.revertJobId = "job-drift-0";
            f.revertStatus = "succeeded";
        }
        f.version = 2;
        findings.put(findingId, f);
    }

    private FindingRow findingRow(StateResource r, String findingId, String severity, String path, JsonNode observed, Instant openedAt) {
        FindingRow f = new FindingRow();
        f.findingId = findingId;
        f.wfrn = r.wfrn();
        f.accountId = r.accountId();
        f.region = r.region();
        f.type = r.type();
        f.severity = severity;
        JsonNode expected = r.attributes() == null ? NODES.nullNode() : r.attributes().path(path.substring(1));
        f.fields = List.of(new Finding.Field(path, expected.isMissingNode() ? NODES.nullNode() : expected.deepCopy(), observed, null));
        f.openedAt = openedAt;
        f.observedAt = openedAt.plus(Duration.ofMinutes(90)).isAfter(clock.instant()) ? clock.instant() : openedAt.plus(Duration.ofMinutes(90));
        return f;
    }

    private void objectSeed(String accountId, String providerId, String name, int cpu, int memoryGb, Map<String, String> tags,
            UnmanagedObject.OwnerGuess guess, String cost, Instant firstSeenAt, Instant seenAt) {
        String key = "vm|" + providerId;
        if (!seeded.add(key)) {
            return;
        }
        ObjectRow o = new ObjectRow();
        o.providerId = providerId;
        o.type = METRICS_TYPE;
        o.region = directory.account(accountId).map(DemoAccount::defaultRegion).orElse("global");
        o.name = name;
        o.foundBy = accountId;
        o.observed = NODES.objectNode().put("name", name).put("cpu", cpu).put("memoryGb", memoryGb).put("powerState", "poweredOn");
        if (!tags.isEmpty()) {
            ObjectNode t = o.observed.putObject("tags");
            tags.forEach(t::put);
        }
        o.firstSeenAt = firstSeenAt;
        o.seenAt = seenAt;
        o.guess = guess;
        o.cost = new Money(new BigDecimal(cost), "USD", BASIS);
        objects.put(key, o);
    }

    private void alarmSeed(StateResource r, String alarmId, String name, String metric, String comparison, double threshold, int window, String by,
            Instant at) {
        if (r == null || !seeded.add("alarm|" + alarmId)) {
            return;
        }
        AlarmRow a = new AlarmRow();
        a.alarmId = alarmId;
        a.accountId = r.accountId();
        a.wfrn = r.wfrn();
        a.type = r.type();
        a.name = name;
        a.metric = metric;
        a.comparison = comparison;
        a.threshold = threshold;
        a.windowMinutes = window;
        a.state = Alarm.INSUFFICIENT;
        a.stateSince = at.minus(Duration.ofDays(2));
        a.createdBy = by;
        a.createdAt = at.minus(Duration.ofDays(2));
        a.updatedAt = a.createdAt;
        alarms.put(a.accountId + "|" + alarmId, a);
    }

    private void recommendationSeed(StateResource r, String recommendationId, String kind, String action, Map<String, Integer> proposed,
            Recommendation.Utilisation utilisation, String cost, String saving, Instant at) {
        if (r == null || !seeded.add(recommendationId)) {
            return;
        }
        RecommendationRow rec = new RecommendationRow();
        rec.recommendationId = recommendationId;
        rec.wfrn = r.wfrn();
        rec.accountId = r.accountId();
        rec.region = r.region();
        rec.type = r.type();
        rec.name = r.name();
        rec.kind = kind;
        rec.action = action;
        rec.current = NODES.objectNode();
        for (String field : List.of("cpu", "memoryGb")) {
            if (r.attributes() != null && r.attributes().has(field)) {
                rec.current.set(field, r.attributes().get(field).deepCopy());
            }
        }
        if (proposed != null) {
            rec.proposed = NODES.objectNode();
            proposed.forEach(rec.proposed::put);
        }
        rec.utilisation = utilisation;
        rec.monthlyCost = new Money(new BigDecimal(cost), "USD", BASIS);
        rec.monthlySaving = new Money(new BigDecimal(saving), "USD", BASIS);
        rec.createdAt = at.minus(Duration.ofHours(3));
        rec.updatedAt = rec.createdAt;
        recommendations.put(recommendationId, rec);
    }

    // ---- pieces

    private void settleAll() {
        findings.values().forEach(this::settle);
    }

    /** A revert whose job has had its time succeeds: the next read finds the resource as it should be, and the finding closes as reverted. */
    private void settle(FindingRow f) {
        if ("running".equals(f.revertStatus) && f.revertStartedAt != null && !clock.instant().isBefore(f.revertStartedAt.plus(REVERT_TAKES))) {
            f.revertStatus = "succeeded";
            close(f, "reverted", f.decidedBy, f.reason);
        }
    }

    private void close(FindingRow f, String resolution, String actor, String reason) {
        Instant now = clock.instant();
        f.status = "closed";
        f.resolution = resolution;
        f.closedAt = now;
        f.observedAt = now;
        f.decidedBy = actor;
        f.reason = reason;
        f.version++;
        try {
            store.resolveDrift(f.accountId, f.wfrn, f.findingId, resolution, actor);
        } catch (RuntimeException e) {
            // the resource is gone or another account's: the finding still closes
        }
    }

    private static void refuseClosed(FindingRow f) {
        if (!f.open()) {
            throw new ConflictException("finding.closed: the finding was closed as " + f.resolution, Map.of("resolution", f.resolution));
        }
    }

    private FindingRow owned(String accountId, String findingId) {
        seed(accountId);
        FindingRow f = findings.get(findingId);
        if (f == null || !f.accountId.equals(accountId)) {
            throw NotFoundException.of("finding", findingId);
        }
        settle(f);
        return f;
    }

    private RecommendationRow ownedRecommendation(String accountId, String recommendationId) {
        seed(accountId);
        RecommendationRow r = recommendations.get(recommendationId);
        if (r == null || !r.accountId.equals(accountId)) {
            throw NotFoundException.of("recommendation", recommendationId);
        }
        return r;
    }

    private ObjectRow visibleObject(String accountId, String region, String type, String providerId) {
        ObjectRow row = objects.get(type + "|" + providerId);
        if (row == null || !row.visibleTo(accountId) || (region != null && !region.equals(row.region))) {
            throw NotFoundException.of("unmanaged object", providerId);
        }
        return row;
    }

    /** A live resource of the account, as the demo state stand-in holds it; 404 otherwise. */
    private StateResource resource(String accountId, String wfrn) {
        Object found = store.get(accountId, wfrn).orElse(null);
        if (!(found instanceof StateResource r)) {
            throw NotFoundException.of("resource", wfrn);
        }
        return r;
    }

    /** The person a change runs for, from the BFF's verified assertion; 409 {@code revert.needsPerson} without one. */
    private String person(String accountId, String assertion) {
        if (assertion == null || assertion.isBlank()) {
            throw new ConflictException("revert.needsPerson: a change runs on behalf of the person who asks for it; the call needs the BFF's "
                    + "X-On-Behalf-Of assertion", Map.of());
        }
        OnBehalfOfAssertions.Claims claims;
        try {
            claims = new OnBehalfOfAssertions.Verifier(trust.jwks(), trust.issuer(), platformAudience, clock).verify(assertion);
        } catch (OnBehalfOfAssertions.InvalidAssertionException e) {
            throw new ConflictException("revert.needsPerson: the X-On-Behalf-Of assertion does not verify (" + e.reason() + ")", Map.of());
        }
        if (claims.accountId() != null && !claims.accountId().equals(accountId)) {
            throw new ConflictException("revert.needsPerson: the X-On-Behalf-Of assertion is for another account", Map.of());
        }
        return claims.subject();
    }

    private Instant lastScan(String accountId) {
        Instant t0 = seededAt.get(accountId);
        return t0 == null ? null : t0.minus(Duration.ofMinutes(10));
    }

    private String accountName(String accountId) {
        return directory.account(accountId).map(DemoAccount::name).orElse(accountId);
    }

    private void evaluate(AlarmRow a, Instant now) {
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        if (a.lastEvaluatedAt != null && !a.lastEvaluatedAt.isBefore(minute)) {
            return;
        }
        Object found = store.get(a.accountId, a.wfrn).orElse(null);
        String state;
        Double last = null;
        if (found instanceof StateResource r) {
            Profile profile = Profile.of(r.name());
            long end = minute.getEpochSecond() / 60;
            double sum = 0;
            for (int i = 0; i < a.windowMinutes; i++) {
                sum += value(a.wfrn, a.metric, profile, end - i);
            }
            last = Math.round(sum / a.windowMinutes * 10) / 10.0;
            boolean firing = "above".equals(a.comparison) ? last > a.threshold : last < a.threshold;
            state = firing ? Alarm.ALARM : Alarm.OK;
        } else {
            state = Alarm.INSUFFICIENT;
        }
        if (!state.equals(a.state)) {
            a.state = state;
            a.stateSince = minute;
        }
        a.lastValue = last;
        a.lastEvaluatedAt = minute;
    }

    private Object replay(String operation, String accountId, String key) {
        return key == null || key.isBlank() ? null : replays.get(operation + "|" + accountId + "|" + key);
    }

    private <T> T remember(String operation, String accountId, String key, T answer) {
        if (key != null && !key.isBlank()) {
            replays.put(operation + "|" + accountId + "|" + key, answer);
        }
        return answer;
    }

    private static <T> Page<T> page(List<T> all, int limit, String after) {
        int from = 0;
        if (after != null && !after.isBlank()) {
            try {
                from = Integer.parseInt(after.strip());
            } catch (NumberFormatException e) {
                throw invalid("cursor", "/after", "after is a cursor this service issued");
            }
            if (from < 0) {
                throw invalid("cursor", "/after", "after is a cursor this service issued");
            }
        }
        int to = Math.min(all.size(), from + limit);
        List<T> items = from >= all.size() ? List.of() : all.subList(from, to);
        return new Page<>(items, to < all.size() ? String.valueOf(to) : null);
    }

    private static String required(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw invalid("required", "/" + field, field + " is required");
        }
        return value.asText();
    }

    private static ArrayNode fieldsJson(List<Finding.Field> fields) {
        ArrayNode out = NODES.arrayNode();
        for (Finding.Field f : fields) {
            ObjectNode one = out.addObject();
            one.put("path", f.path());
            one.set("expected", f.expected() == null ? NODES.nullNode() : f.expected());
            one.set("observed", f.observed() == null ? NODES.nullNode() : f.observed());
        }
        return out;
    }

    private static long ceil(long value, long step) {
        return Math.floorDiv(value + step - 1, step) * step;
    }

    static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, field, message)));
    }

    /** One series' value at a minute: the machine's level, a daily swing and a little noise, all fixed by the name and the minute. */
    static double value(String wfrn, String series, Profile p, long epochMinute) {
        double base = switch (series) {
            case "cpuPercent" -> p.cpu();
            case "memoryPercent" -> p.memory();
            default -> p.disk();
        };
        double swing = switch (series) {
            case "cpuPercent" -> Math.min(4, base * 0.15);
            case "memoryPercent" -> Math.min(3, base * 0.05);
            default -> 0.5;
        };
        double day = Math.sin(2 * Math.PI * Math.floorMod(epochMinute, 1440) / 1440.0);
        long mixed = mix(wfrn.hashCode() * 31L + series.hashCode(), epochMinute);
        double noise = (Math.floorMod(mixed, 1000) / 1000.0 - 0.5) * Math.min(2, base * 0.1);
        double v = Math.max(0, Math.min(100, base + swing * day + noise));
        return Math.round(v * 10) / 10.0;
    }

    private static long mix(long a, long b) {
        long x = a * 0x9E3779B97F4A7C15L + b;
        x ^= x >>> 31;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 29;
        return x;
    }

    /** How busy a demo machine is, in percent: web-01 works, batch-01 idles, web-02 is far larger than it needs. */
    record Profile(double cpu, double memory, double disk) {

        static Profile of(String name) {
            if (name == null) {
                return new Profile(20, 40, 35);
            }
            return switch (name) {
                case "web-01" -> new Profile(38, 61, 47);
                case "batch-01" -> new Profile(2, 9, 12);
                case "web-02" -> new Profile(9, 18, 30);
                default -> {
                    int h = Math.floorMod(name.hashCode(), 15);
                    yield new Profile(18 + h, 40 + h, 35 + h);
                }
            };
        }
    }

    // ---- the rows, mutable here and turned into the contract's shapes on every read

    private static final class FindingRow {
        String findingId;
        String wfrn;
        String accountId;
        String region;
        String type;
        String severity;
        String status = "open";
        long version = 1;
        List<Finding.Field> fields = List.of();
        Instant openedAt;
        Instant observedAt;
        Instant closedAt;
        String resolution;
        String decidedBy;
        String reason;
        String revertJobId;
        String revertStatus;
        Instant revertStartedAt;
        Instant ignoredUntil;
        /** Why a revert cannot run, as {@code finding.notRevertable} says it; null when it can. */
        String refusal;

        boolean open() {
            return "open".equals(status);
        }

        Finding view() {
            return new Finding(findingId, wfrn, accountId, region, type, status, severity, version, fields, openedAt, observedAt, closedAt, resolution,
                    decidedBy, reason, revertJobId, revertStatus, ignoredUntil, null);
        }
    }

    private static final class ObjectRow {
        String providerId;
        String type;
        String region;
        String name;
        /** The account whose discovery found it. */
        String foundBy;
        ObjectNode observed;
        Instant seenAt;
        Instant firstSeenAt;
        UnmanagedObject.OwnerGuess guess;
        Money cost;
        UnmanagedObject.Assignment assignment;
        boolean imported;

        boolean visibleTo(String accountId) {
            return !imported && (foundBy.equals(accountId) || (assignment != null && assignment.accountId().equals(accountId)));
        }

        UnmanagedObject view() {
            return new UnmanagedObject(providerId, type, region, name, observed.deepCopy(), seenAt, firstSeenAt, guess, cost, assignment, null);
        }
    }

    private static final class RunRow {
        String runId;
        String accountId;
        String type;
        String region;
        String status;
        Instant startedAt;
        Instant endedAt;
        Integer seen;
        Integer unmanaged;

        DiscoveryRun view() {
            return new DiscoveryRun(runId, type, region, status, startedAt, endedAt, seen, unmanaged, null);
        }
    }

    private static final class AlarmRow {
        String alarmId;
        String accountId;
        String wfrn;
        String type;
        String name;
        String metric;
        String comparison;
        double threshold;
        int windowMinutes;
        String state;
        Instant stateSince;
        Double lastValue;
        Instant lastEvaluatedAt;
        String createdBy;
        Instant createdAt;
        Instant updatedAt;

        Alarm view() {
            return new Alarm(alarmId, wfrn, accountId, type, name, metric, comparison, threshold, windowMinutes, state, stateSince, lastValue,
                    lastEvaluatedAt, createdBy, createdAt, updatedAt);
        }
    }

    private static final class RecommendationRow {
        String recommendationId;
        String wfrn;
        String accountId;
        String region;
        String type;
        String name;
        String kind;
        String action;
        String status = "open";
        ObjectNode current;
        ObjectNode proposed;
        Recommendation.Utilisation utilisation;
        Money monthlyCost;
        Money monthlySaving;
        String jobId;
        String decidedBy;
        String reason;
        Instant dismissedUntil;
        Instant createdAt;
        Instant updatedAt;

        boolean open() {
            return "open".equals(status);
        }

        Recommendation view() {
            return new Recommendation(recommendationId, wfrn, accountId, region, type, name, kind, action, status, current == null ? null : current.deepCopy(),
                    proposed == null ? null : proposed.deepCopy(), utilisation, monthlyCost, monthlySaving, jobId, decidedBy, reason, dismissedUntil,
                    createdAt, updatedAt, null);
        }
    }
}
