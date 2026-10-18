package com.iaf.platform.common.adapters.jdbc;

import java.util.function.Supplier;

/**
 * An in-memory copy of a small, read-mostly table (the workflow versions, the providers, the
 * resource types) that stays right across backend instances: every write moves the table's row
 * in {@code store_revisions} inside its own transaction, and a read reloads the copy only when
 * that number moved. A read costs one single-row query instead of the whole table, which matters
 * for the generated routes, rebuilt from the registry on every call.
 */
public final class Revisioned<T> {

    private record Snapshot<T>(long revision, T value) {
    }

    private final Jdbc jdbc;
    private final String store;
    private final Supplier<T> load;
    private volatile Snapshot<T> current;

    public Revisioned(Jdbc jdbc, String store, Supplier<T> load) {
        this.jdbc = jdbc;
        this.store = store;
        this.load = load;
    }

    /** The table as of its latest revision. */
    public T get() {
        long revision = jdbc.revision(store);
        Snapshot<T> s = current;
        if (s == null || s.revision() != revision) {
            s = new Snapshot<>(revision, load.get());
            current = s;
        }
        return s.value();
    }

    /** Record a write; call it inside the writing transaction. */
    public void bump() {
        jdbc.bump(store);
    }
}
