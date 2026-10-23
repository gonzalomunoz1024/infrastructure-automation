package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The walk of one list's pages, kept between scans: an account's resources of a type in a region and
 * environment as {@code :observe-list} pages them, or every account's imported resources of a type
 * in a region in its {@code :discover} pages. One shard turn at a time holds a walk (until
 * {@code heldUntil}, renewed with every page), so the 64 shards' turns that all find the list's
 * resources due walk it once; a walk cut short (the budget, a 429, a turn's limit) keeps its cursor
 * and the next turn goes on from there. Every resource a walk meets is read, due or not; what a
 * finished walk met, by tier, says when the list is worth walking again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Walk(
        String key,
        String holder,
        Instant heldUntil,
        Instant startedAt,
        String cursor,
        long hot,
        long warm,
        long cold,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        long lastHot,
        long lastWarm,
        long lastCold,
        long rev) {

    /** A list nobody walked yet. */
    public static Walk none(String key) {
        return new Walk(key, null, null, null, null, 0, 0, 0, null, null, 0, 0, 0, 0);
    }

    /** The key of an account's list of a type in a region and environment. */
    public static String listKey(ScanGroup group) {
        return "list:" + group.type() + ":" + group.region() + ":" + group.environment() + ":" + group.accountId();
    }

    /** The key of a type's discover pages in a region: one walk reads every account's imported resources of the type. */
    public static String discoverKey(String type, String region) {
        return "discover:" + type + ":" + region;
    }

    /** Whether another turn holds the walk now. */
    public boolean heldByAnother(String me, Instant now) {
        return holder != null && !holder.equals(me) && heldUntil != null && heldUntil.isAfter(now);
    }

    /** Whether a walk was started and not finished: the next one goes on from its cursor. */
    public boolean underway() {
        return startedAt != null;
    }

    /** How many resources the last finished walk met. */
    public long size() {
        return lastHot + lastWarm + lastCold;
    }

    /** The walk held by {@code me} until {@code until}: a new one from the first page, or the one under way from its cursor. */
    public Walk heldBy(String me, Instant now, Instant until) {
        boolean fresh = startedAt == null;
        return new Walk(key, me, until, fresh ? now : startedAt, fresh ? null : cursor, fresh ? 0 : hot, fresh ? 0 : warm, fresh ? 0 : cold,
                lastStartedAt, lastFinishedAt, lastHot, lastWarm, lastCold, rev);
    }

    /** After a page: where the next one starts, what was met so far, the hold renewed. */
    public Walk advanced(String next, long metHot, long metWarm, long metCold, Instant until) {
        return new Walk(key, holder, until, startedAt, next, hot + metHot, warm + metWarm, cold + metCold, lastStartedAt, lastFinishedAt, lastHot,
                lastWarm, lastCold, rev);
    }

    /** Let go before the end: the cursor stays for whoever goes on. */
    public Walk released() {
        return new Walk(key, null, null, startedAt, cursor, hot, warm, cold, lastStartedAt, lastFinishedAt, lastHot, lastWarm, lastCold, rev);
    }

    /** Let go and forget the walk under way: the list could not be walked. */
    public Walk abandoned() {
        return new Walk(key, null, null, null, null, 0, 0, 0, lastStartedAt, lastFinishedAt, lastHot, lastWarm, lastCold, rev);
    }

    /** The last page was read: what the walk met becomes the list's last count. */
    public Walk finished(Instant now) {
        return new Walk(key, null, null, null, null, 0, 0, 0, startedAt, now, hot, warm, cold, rev);
    }

    public Walk withRev(long newRev) {
        return new Walk(key, holder, heldUntil, startedAt, cursor, hot, warm, cold, lastStartedAt, lastFinishedAt, lastHot, lastWarm, lastCold, newRev);
    }

    /**
     * The list's resources expected due by now: the last walk read every one of them, so each tier's
     * count is due again once 80% of its interval has passed since that walk began (the earliest
     * {@link TierRules#nextCheck} gives). Zero for a list never walked to the end.
     */
    public long dueBy(Instant now, TierRules tiers, Duration typeInterval) {
        if (lastStartedAt == null) {
            return 0;
        }
        long due = 0;
        long[] counts = {lastHot, lastWarm, lastCold};
        for (Tier t : Tier.values()) {
            Duration interval = tiers.intervalOf(t, typeInterval);
            if (!now.isBefore(lastStartedAt.plusMillis(interval.toMillis() * 8 / 10))) {
                due += counts[t.ordinal()];
            }
        }
        return due;
    }

    /**
     * Whether to walk the list now rather than read its due resources in batches, by what each costs
     * the observe budget, which counts provider reads: a walk is a read per page of {@code pageSize}
     * resources of the list, a batch a read per resource in it. {@code estimatedDue} is the list's
     * resources due now across the shards (a shard holds an even share of them, so its own count
     * times the shard count). A list never walked to the end is walked once at least
     * {@code listThreshold} are due; after that, its last walk's count gives its size and, by tier,
     * how many come due together, so a list with only a handful due (a few hot resources among many
     * warm ones) has them read in batches, and the list is walked when its pages cost no more than
     * its due resources would.
     */
    public boolean worthWalking(long estimatedDue, Instant now, TierRules tiers, Duration typeInterval, int pageSize, int listThreshold) {
        if (estimatedDue <= 0) {
            return false;
        }
        if (lastStartedAt == null) {
            return estimatedDue >= listThreshold;
        }
        long size = Math.max(1, size());
        long due = Math.min(size, Math.max(estimatedDue, dueBy(now, tiers, typeInterval)));
        long pages = (size + pageSize - 1) / pageSize;
        return pages <= due;
    }
}
