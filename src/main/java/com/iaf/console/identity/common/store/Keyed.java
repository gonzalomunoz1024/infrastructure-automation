package com.iaf.console.identity.common.store;

/** How a store reads a record's id, account, name and version. */
public interface Keyed<T> {

    String id(T record);

    String accountId(T record);

    String name(T record);

    long version(T record);
}
