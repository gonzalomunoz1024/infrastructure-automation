package com.iaf.console.drift.load;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Shards;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The watch list of a synthetic estate, compact enough for ten million watches: what a watch is
 * comes from the estate, and only what scans change is kept, in arrays by resource index (due time,
 * last check, last observation and its digest, tier, revision). A watch is built when a scan asks for
 * it. Each shard and tier has a due queue (a heap of due second and index, with stale entries dropped
 * when they come up), so {@code findDue} costs what it returns. Saves are conditional on the
 * revision as in the real stores, and every read a save records is measured against its tier's
 * interval: how long since the resource was read before.
 */
final class EstateWatches implements WatchStorePort {

    private static final int INDEX_BITS = 25;
    private static final long INDEX_MASK = (1L << INDEX_BITS) - 1;
    private static final Tier[] TIERS = Tier.values();

    private final Estate estate;
    private final TierRules tiers;
    private final int shards;
    private final Instant base;
    private final long[] next;
    private final long[] checked;
    private final long[] observedAt;
    private final int[] rev;
    private final byte[] tier;
    private final byte[] digest;
    private final short[] shard;
    private final LongHeap[] due;
    private final Map<Integer, String> openFindings = new HashMap<>();
    private final Map<Integer, Long> driftedAt = new HashMap<>();
    private final List<String> digests = new ArrayList<>();
    private final ObjectNode[] desired;
    private final ReadStats reads;

    EstateWatches(Estate estate, TierRules tiers, int shards, ReadStats reads) {
        this.estate = estate;
        this.tiers = tiers;
        this.shards = shards;
        this.reads = reads;
        this.base = estate.start().minus(Duration.ofDays(2));
        int n = Math.toIntExact(estate.size());
        next = new long[n];
        checked = new long[n];
        observedAt = new long[n];
        rev = new int[n];
        tier = new byte[n];
        digest = new byte[n];
        shard = new short[n];
        due = new LongHeap[shards * TIERS.length];
        for (int h = 0; h < due.length; h++) {
            due[h] = new LongHeap();
        }
        desired = new ObjectNode[Estate.KINDS.size()];
        for (int k = 0; k < desired.length; k++) {
            desired[k] = Json.objectNode(Estate.KINDS.get(k).attributes());
        }
        digests.add(null);
    }

    /** Fills the list as a steady state: each watch last read somewhere in its tier's interval and due at the matching time. */
    void seed(Map<String, String> typeDigests) {
        Instant start = estate.start();
        long startMs = start.toEpochMilli();
        for (long i = 0; i < estate.size(); i++) {
            int at = (int) i;
            String wfrn = estate.wfrn(i);
            shard[at] = (short) Shards.of(wfrn, shards);
            Watch w = build(at, wfrn);
            Tier t = tiers.tierOf(w, start);
            long interval = tiers.intervalOf(t, null).toMillis();
            // the resource was read at a random point of its last cycle and is due 80% to 90% of an interval after that
            long sinceRead = (long) (estate.draw(i, 1) * interval * 0.85);
            long cycle = interval * 8 / 10 + Math.floorMod(wfrn.hashCode(), Math.max(1, interval / 10));
            checked[at] = startMs - sinceRead;
            next[at] = checked[at] + cycle;
            observedAt[at] = startMs - (long) (estate.draw(i, 2) * Duration.ofHours(23).toMillis());
            digest[at] = (byte) intern(typeDigests.get(estate.kind(estate.groupOf(i)).type()));
            tier[at] = (byte) t.ordinal();
            rev[at] = 1;
            enqueue(at);
        }
    }

    // what scans call

    @Override
    public Mono<Watch> find(String wfrn) {
        return Mono.fromSupplier(() -> {
            int at = index(wfrn);
            return at < 0 ? null : build(at, wfrn);
        });
    }

    @Override
    public Flux<Watch> findByWfrns(Collection<String> wfrns) {
        return Flux.defer(() -> {
            List<Watch> out = new ArrayList<>(wfrns.size());
            for (String wfrn : wfrns) {
                int at = index(wfrn);
                if (at >= 0) {
                    out.add(build(at, wfrn));
                }
            }
            return Flux.fromIterable(out);
        });
    }

    @Override
    public Flux<Watch> findDue(int shardNo, Tier wanted, Instant now, int limit) {
        return Flux.defer(() -> Flux.fromIterable(due(shardNo, wanted, now, limit)));
    }

    private List<Watch> due(int shardNo, Tier wanted, Instant now, int limit) {
        long nowSec = seconds(now.toEpochMilli());
        List<Integer> heaps = new ArrayList<>();
        for (Tier t : TIERS) {
            if (wanted == null || wanted == t) {
                heaps.add(shardNo * TIERS.length + t.ordinal());
            }
        }
        List<Long> taken = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<Watch> out = new ArrayList<>();
        while (out.size() < limit) {
            int best = -1;
            for (int h : heaps) {
                LongHeap heap = due[h];
                // drop the entries a later save made stale
                while (!heap.isEmpty() && !current(heap.peek(), h % TIERS.length)) {
                    heap.pop();
                }
                if (!heap.isEmpty() && (heap.peek() >>> INDEX_BITS) <= nowSec && (best < 0 || heap.peek() < due[best].peek())) {
                    best = h;
                }
            }
            if (best < 0) {
                break;
            }
            long entry = due[best].pop();
            taken.add(entry);
            int at = (int) (entry & INDEX_MASK);
            if (seen.add(at)) {
                out.add(build(at, estate.wfrn(at)));
            }
        }
        // what was returned stays due until a save moves it
        for (int k = 0; k < taken.size(); k++) {
            long entry = taken.get(k);
            int at = (int) (entry & INDEX_MASK);
            due[shard[at] * TIERS.length + tier[at]].push(entry);
        }
        return out;
    }

    private boolean current(long entry, int tierOrdinal) {
        int at = (int) (entry & INDEX_MASK);
        return tier[at] == tierOrdinal && seconds(next[at]) == (entry >>> INDEX_BITS);
    }

    @Override
    public Flux<Watch> findByProviderId(String providerId, String type) {
        return Flux.defer(() -> {
            long i = Estate.indexOfProviderId(providerId);
            if (i < 0 || i >= estate.size() || !estate.resource(i).imported()) {
                return Flux.empty();
            }
            return Flux.just(build((int) i, estate.wfrn(i)));
        });
    }

    @Override
    public Mono<Watch> save(Watch w) {
        return Mono.fromCallable(() -> {
            int at = index(w.wfrn());
            if (at < 0 || rev[at] != w.rev()) {
                throw new Conflict(w.wfrn());
            }
            if (!w.ignored().isEmpty()) {
                throw new IllegalStateException("the load estate has no silences");
            }
            long newChecked = millis(w.lastCheckedAt());
            if (newChecked > checked[at]) {
                Tier was = TIERS[tier[at]];
                reads.read(was, newChecked - checked[at], tiers.intervalOf(was, null).toMillis());
                checked[at] = newChecked;
            }
            next[at] = millis(w.nextCheckAt());
            observedAt[at] = millis(w.lastObservationAt());
            digest[at] = (byte) intern(w.lastObservedDigest());
            tier[at] = (byte) w.tier().ordinal();
            if (w.openFindingId() == null) {
                openFindings.remove(at);
            } else {
                openFindings.put(at, w.openFindingId());
            }
            if (w.lastDriftAt() == null) {
                driftedAt.remove(at);
            } else {
                driftedAt.put(at, w.lastDriftAt().toEpochMilli());
            }
            rev[at]++;
            enqueue(at);
            return w.withRev(rev[at]);
        });
    }

    // what the report reads

    /** Per tier: watches, and those whose last read is older than their tier's interval now. */
    long[][] staleness(Instant now) {
        long nowMs = now.toEpochMilli();
        long[][] out = new long[TIERS.length][2];
        long[] intervals = new long[TIERS.length];
        for (Tier t : TIERS) {
            intervals[t.ordinal()] = tiers.intervalOf(t, null).toMillis();
        }
        for (int at = 0; at < next.length; at++) {
            int t = tier[at];
            out[t][0]++;
            if (nowMs - checked[at] > intervals[t]) {
                out[t][1]++;
            }
        }
        return out;
    }

    int openFindings() {
        return openFindings.size();
    }

    // the rest of the port, which scans do not use

    @Override
    public Flux<String> listAccounts() {
        return Flux.range(0, estate.accounts()).map(Estate::accountId);
    }

    @Override
    public Mono<Page<Watch>> list(AccountScope scope, String type, int limit, String after) {
        return Mono.error(new UnsupportedOperationException("not part of a scan"));
    }

    @Override
    public Mono<Instant> findLastCheckedAt(AccountScope scope) {
        return Mono.error(new UnsupportedOperationException("not part of a scan"));
    }

    @Override
    public Mono<Void> delete(String wfrn) {
        return Mono.error(new UnsupportedOperationException("not part of a scan"));
    }

    @Override
    public Mono<Long> count() {
        return Mono.just(estate.size());
    }

    // helpers

    private Watch build(int at, String wfrn) {
        Estate.Resource r = estate.resource(at);
        Estate.Group g = r.group();
        Estate.Kind k = estate.kind(g);
        Long drifted = driftedAt.get(at);
        return new Watch(wfrn, Estate.accountId(g.account()), Estate.REGION, k.type(), k.service(), estate.environment(g), k.type() + "-" + at,
                "reg-" + Long.toString(at, 36), r.imported() ? estate.providerId(at) : null, "active", 1, desired[g.kind()], Map.of(), r.updatedAt(),
                shard[at], TIERS[tier[at]], instant(next[at]), instant(checked[at]), instant(observedAt[at]), digests.get(digest[at]),
                openFindings.get(at), drifted == null ? null : Instant.ofEpochMilli(drifted), List.of(), r.imported() ? "discover" : null, rev[at]);
    }

    private void enqueue(int at) {
        due[shard[at] * TIERS.length + tier[at]].push((seconds(next[at]) << INDEX_BITS) | at);
    }

    private int index(String wfrn) {
        long i = Estate.indexOf(wfrn);
        return i < 0 || i >= estate.size() ? -1 : (int) i;
    }

    private long seconds(long epochMillis) {
        return Math.max(0, (epochMillis - base.toEpochMilli()) / 1000);
    }

    private int intern(String d) {
        if (d == null) {
            return 0;
        }
        int at = digests.indexOf(d);
        if (at < 0) {
            digests.add(d);
            at = digests.size() - 1;
        }
        if (at > Byte.MAX_VALUE) {
            throw new IllegalStateException("more observation digests than the load estate expects");
        }
        return at;
    }

    private static long millis(Instant t) {
        return t == null ? 0 : t.toEpochMilli();
    }

    private static Instant instant(long ms) {
        return ms == 0 ? null : Instant.ofEpochMilli(ms);
    }
}
