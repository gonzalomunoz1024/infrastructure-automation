package com.iaf.platform.common.adapters.mongo;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * An in-memory copy of a small, read-mostly collection (the workflow versions, the providers, the
 * resource types) that stays right across backend instances: every write ({@link #write}) moves the
 * collection's number in {@code store_revisions} once the write is done, also when it throws, and a
 * read reloads the copy when that number moved. A read costs one single-document query instead of
 * the whole collection, which matters for the generated routes, rebuilt from the registry on every
 * call.
 *
 * <p>The write and its move are two writes, with no transaction around them. A copy older than
 * {@link #MAX_AGE} is reloaded even when the number did not move, so a write whose move was lost
 * (the instance died between the two) reaches every instance, its writer's included, within that
 * age instead of waiting for some unrelated write.
 */
public final class Revisioned<T> {

    /** How long a copy is served while its revision stays; the first read after that reloads it. */
    public static final Duration MAX_AGE = Duration.ofSeconds(30);

    private record Snapshot<T>(long revision, long loadedAt, T value) {
    }

    private final Mongo mongo;
    private final String store;
    private final Supplier<T> load;
    private final long maxAgeNanos;
    private final LongSupplier nanoTime;
    private volatile Snapshot<T> current;

    public Revisioned(Mongo mongo, String store, Supplier<T> load) {
        this(mongo, store, load, MAX_AGE, System::nanoTime);
    }

    /** With another maximum age and ticker ({@link System#nanoTime} by default), for tests. */
    Revisioned(Mongo mongo, String store, Supplier<T> load, Duration maxAge, LongSupplier nanoTime) {
        this.mongo = mongo;
        this.store = store;
        this.load = load;
        this.maxAgeNanos = maxAge.toNanos();
        this.nanoTime = nanoTime;
    }

    /** The collection as of its latest revision, at most {@link #MAX_AGE} old. */
    public T get() {
        long revision = mongo.revision(store);
        long now = nanoTime.getAsLong();
        Snapshot<T> s = current;
        if (s == null || s.revision() != revision || now - s.loadedAt() >= maxAgeNanos) {
            s = new Snapshot<>(revision, now, load.get());
            current = s;
        }
        return s.value();
    }

    /**
     * Runs a write to the collection, then moves its revision, in a {@code finally}: a write that
     * throws may still have landed (the server applied it and the answer was lost, or the first of
     * its writes went through), so it moves the revision too; a move that records nothing costs one
     * reload. When the write threw, a failed move is added to its exception rather than replacing it.
     */
    public void write(Runnable write) {
        Throwable failed = null;
        try {
            write.run();
        } catch (RuntimeException | Error e) {
            failed = e;
            throw e;
        } finally {
            try {
                mongo.bump(store);
            } catch (RuntimeException e) {
                if (failed == null) {
                    throw e;
                }
                failed.addSuppressed(e);
            }
        }
    }
}
