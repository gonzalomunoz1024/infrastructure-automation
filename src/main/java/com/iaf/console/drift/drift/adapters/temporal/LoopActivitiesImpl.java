package com.iaf.console.drift.drift.adapters.temporal;

import java.time.Duration;

import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.application.RevertFollower;
import com.iaf.console.drift.drift.application.ScanReport;
import com.iaf.console.drift.drift.application.ShardScanner;
import com.iaf.console.drift.drift.domain.Tier;

/** The turns: the application's scan of one shard and tier, the inventory sync, the follower pass. */
public final class LoopActivitiesImpl implements LoopActivities {

    private static final Duration LIMIT = Duration.ofMinutes(14);

    private final ShardScanner scanner;
    private final InventorySync sync;
    private final RevertFollower follower;

    public LoopActivitiesImpl(ShardScanner scanner, InventorySync sync, RevertFollower follower) {
        this.scanner = scanner;
        this.sync = sync;
        this.follower = follower;
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
            default -> throw new IllegalArgumentException("unknown loop kind " + spec.kind());
        };
    }
}
