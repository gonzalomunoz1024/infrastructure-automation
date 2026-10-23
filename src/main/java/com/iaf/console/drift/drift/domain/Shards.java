package com.iaf.console.drift.drift.domain;

/** The shard of a resource: its wfrn's hash modulo the shard count (the plan's 64 per region). */
public final class Shards {

    public static final int DEFAULT = 64;

    private Shards() {
    }

    public static int of(String wfrn, int shards) {
        return Math.floorMod(wfrn.hashCode(), Math.max(1, shards));
    }
}
