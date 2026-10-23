package com.iaf.console.drift.drift.adapters.temporal;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ScanReport;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.domain.Tier;

import reactor.core.publisher.Mono;

/**
 * The turns: the application's scan of one shard and tier, the inventory sync, the follower pass,
 * and the turns other parts of the service hand in by loop kind (the alarm evaluation, the
 * utilisation pass), each answering a one-line summary.
 */
public final class LoopActivitiesImpl implements LoopActivities {

    private static final Duration LIMIT = Duration.ofMinutes(14);

    private final ShardScanner scanner;
    private final InventorySync sync;
    private final RevertFollower follower;
    private final Map<String, Supplier<Mono<String>>> more;

    public LoopActivitiesImpl(ShardScanner scanner, InventorySync sync, RevertFollower follower) {
        this(scanner, sync, follower, Map.of());
    }

    /** With further turns by loop kind. */
    public LoopActivitiesImpl(ShardScanner scanner, InventorySync sync, RevertFollower follower, Map<String, Supplier<Mono<String>>> more) {
        this.scanner = scanner;
        this.sync = sync;
        this.follower = follower;
        this.more = Map.copyOf(more);
    }

    @Override
    public String tick(LoopSpec spec) {
        return switch (spec.kind()) {
            case LoopSpec.SCAN -> {
                ScanReport r = scanner.scan(spec.shard(), Tier.of(spec.tier())).block(LIMIT);
                yield r == null ? "nothing due" : r.checked() + " read, " + r.opened() + " opened, " + r.closed() + " closed, " + r.deferred() + " deferred";
            }
            case LoopSpec.SYNC -> {
                InventorySync.SyncReport r = sync.tick().block(LIMIT);
                yield r == null ? "nothing" : r.seen() + " seen, " + r.added() + " added, " + r.changed() + " changed, " + r.removed() + " removed";
            }
            case LoopSpec.FOLLOW -> {
                Integer ended = follower.tick().block(LIMIT);
                yield (ended == null ? 0 : ended) + " revert job(s) ended";
            }
            default -> {
                Supplier<Mono<String>> turn = more.get(spec.kind());
                if (turn == null) {
                    throw new IllegalArgumentException("unknown loop kind " + spec.kind());
                }
                String summary = turn.get().block(LIMIT);
                yield summary == null ? "nothing" : summary;
            }
        };
    }
}
