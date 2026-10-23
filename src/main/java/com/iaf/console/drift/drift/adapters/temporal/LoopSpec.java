package com.iaf.console.drift.drift.adapters.temporal;

/**
 * One loop of the {@code temporal} profile: what it does each turn ({@code scan} of one shard and
 * tier, {@code sync} of the inventory, {@code follow} of revert jobs, {@code alarms} evaluated,
 * {@code utilisation} read), how long it sleeps between turns, and how many turns it takes before it
 * continues as new (which keeps its history short).
 */
public record LoopSpec(String kind, String region, int shard, String tier, long intervalMillis, int iterations) {

    public static final String SCAN = "scan";
    public static final String SYNC = "sync";
    public static final String FOLLOW = "follow";
    /** The evaluation of every alarm (monitoring). */
    public static final String ALARMS = "alarms";
    /** The utilisation pass's wake (rightsizing). */
    public static final String UTILISATION = "utilisation";

    /** The workflow id: one loop per region, kind, shard and tier, so a second start finds the first. */
    public String workflowId() {
        return switch (kind) {
            case SCAN -> "console-drift-" + region + "-scan-" + shard + "-" + tier;
            default -> "console-drift-" + region + "-" + kind;
        };
    }
}
