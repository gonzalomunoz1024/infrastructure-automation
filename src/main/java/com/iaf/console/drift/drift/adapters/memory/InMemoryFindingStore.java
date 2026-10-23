package com.iaf.console.drift.drift.adapters.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingQuery;
import com.iaf.console.drift.drift.domain.FindingStats;
import com.iaf.console.drift.drift.domain.FindingStatus;
import com.iaf.console.drift.drift.domain.FindingsSummary;
import com.iaf.console.drift.drift.domain.RevertStatus;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Findings in memory, for the demo and the tests. A write never goes back in version and a closed
 * finding stays closed: a stale write answers what is stored, as the MongoDB store does. One
 * finding is open per resource: opening a second one answers the one already open.
 */
public final class InMemoryFindingStore implements FindingStorePort {

    /** Newest first, then by id: the order of every list. */
    static final Comparator<Finding> ORDER = Comparator.comparing(Finding::openedAt).reversed().thenComparing(Finding::findingId,
            Comparator.reverseOrder());

    private final Map<String, Finding> byId = new ConcurrentHashMap<>();

    @Override
    public Mono<Finding> find(AccountScope scope, String findingId) {
        return Mono.justOrEmpty(byId.get(findingId)).filter(f -> f.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<Finding> findAny(String findingId) {
        return Mono.justOrEmpty(byId.get(findingId));
    }

    @Override
    public Mono<Page<Finding>> list(AccountScope scope, FindingQuery q) {
        return Mono.fromSupplier(() -> {
            Instant cursorAt = q.after() == null ? null : Instant.parse(String.valueOf(q.after().sortValue()));
            String cursorId = q.after() == null ? null : q.after().id();
            List<Finding> fetched = byId.values().stream()
                    .filter(f -> f.accountId().equals(scope.accountId()))
                    .filter(f -> matches(q, f))
                    .sorted(ORDER)
                    .filter(f -> cursorAt == null || f.openedAt().isBefore(cursorAt)
                            || f.openedAt().equals(cursorAt) && f.findingId().compareTo(cursorId) < 0)
                    .limit(q.limit() + 1L)
                    .toList();
            return Page.from(fetched, q.limit(), f -> new Cursor(f.openedAt().toString(), f.findingId()));
        });
    }

    /** Whether a finding passes the query's filters (the account aside). */
    static boolean matches(FindingQuery q, Finding f) {
        if (q.region() != null && !q.region().equals(f.region())) {
            return false;
        }
        if (!q.wantsStatus(f.status())) {
            return false;
        }
        if (q.severity() != null && q.severity() != f.severity()) {
            return false;
        }
        if (q.type() != null && !q.type().equals(f.type())) {
            return false;
        }
        if (q.openedAfter() != null && !f.openedAt().isAfter(q.openedAfter())) {
            return false;
        }
        if (q.wfrn() != null) {
            return q.wfrnIsPattern() ? Wfrn.matches(q.wfrn(), f.wfrn()) : q.wfrn().equals(f.wfrn());
        }
        return true;
    }

    @Override
    public Mono<FindingsSummary> countOpen(AccountScope scope, String region) {
        return Mono.fromSupplier(() -> {
            Map<String, Long> byType = new TreeMap<>();
            Map<String, Long> bySeverity = new TreeMap<>();
            long open = 0;
            for (Finding f : byId.values()) {
                if (f.status() != FindingStatus.OPEN || !f.accountId().equals(scope.accountId()) || region != null && !region.equals(f.region())) {
                    continue;
                }
                open++;
                byType.merge(f.type(), 1L, Long::sum);
                bySeverity.merge(f.severity().id(), 1L, Long::sum);
            }
            return new FindingsSummary(open, byType, bySeverity, null);
        });
    }

    @Override
    public Mono<FindingStats> countWindow(AccountScope scope, Instant since, Instant until) {
        return tally(scope.accountId(), since, until);
    }

    @Override
    public Mono<FindingStats> countWindowEveryAccount(Instant since, Instant until) {
        return tally(null, since, until);
    }

    private Mono<FindingStats> tally(String accountId, Instant since, Instant until) {
        return Mono.fromSupplier(() -> {
            FindingStats.Tally tally = new FindingStats.Tally(accountId, since, until);
            byId.values().stream().filter(f -> accountId == null || f.accountId().equals(accountId)).forEach(tally::add);
            return tally.stats();
        });
    }

    @Override
    public Mono<Finding> findOpenByWfrn(String wfrn) {
        return Flux.fromIterable(byId.values()).filter(f -> f.status() == FindingStatus.OPEN && f.wfrn().equals(wfrn)).next();
    }

    @Override
    public Flux<Finding> findReverting(int limit) {
        return Flux.fromIterable(byId.values()).filter(f -> f.status() == FindingStatus.OPEN && f.revertStatus() == RevertStatus.RUNNING).take(limit);
    }

    @Override
    public Mono<Finding> save(Finding finding) {
        return Mono.fromSupplier(() -> store(finding));
    }

    /** One write at a time, so the one-open-finding-per-resource rule holds as the unique index holds it in MongoDB. */
    private synchronized Finding store(Finding finding) {
        Finding current = byId.get(finding.findingId());
        if (current != null && (current.status() == FindingStatus.CLOSED || current.version() > finding.version())) {
            return current;
        }
        if (current == null && finding.open()) {
            for (Finding other : byId.values()) {
                if (other.open() && other.wfrn().equals(finding.wfrn())) {
                    return other;
                }
            }
        }
        byId.put(finding.findingId(), finding);
        return finding;
    }

    /** For tests: everything stored. */
    public List<Finding> all() {
        return List.copyOf(byId.values());
    }
}
