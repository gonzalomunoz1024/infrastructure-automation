package com.iaf.platform.common.adapters.mongo;

import java.util.function.Supplier;

/**
 * An in-memory copy of a small, read-mostly collection (the workflow versions, the providers, the
 * resource types) that stays right across backend instances: every write moves the collection's
 * number in {@code store_revisions} once the write is done, and a read reloads the copy only when
 * that number moved. A read costs one single-document query instead of the whole collection,
 * which matters for the generated routes, rebuilt from the registry on every call.
 */
public final class Revisioned<T> {

    private record Snapshot<T>(long revision, T value) {
    }

    private final Mongo mongo;
    private final String store;
    private final Supplier<T> load;
    private volatile Snapshot<T> current;

    public Revisioned(Mongo mongo, String store, Supplier<T> load) {
        this.mongo = mongo;
        this.store = store;
        this.load = load;
    }

    /** The collection as of its latest revision. */
    public T get() {
        long revision = mongo.revision(store);
        Snapshot<T> s = current;
        if (s == null || s.revision() != revision) {
            s = new Snapshot<>(revision, load.get());
            current = s;
        }
        return s.value();
    }

    /** Record a write; call it once the write is done. */
    public void bump() {
        mongo.bump(store);
    }
}
