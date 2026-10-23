package com.iaf.console.drift.drift.domain;

/**
 * The unit of a paged read: one type, region, environment and account, what {@code :observe-list}
 * takes. Resources imported from discovery form one group per type and region, without an
 * environment or an account: they are read from the type's discover pages of every environment of
 * the region, which list every account's objects, so one walk serves them all.
 */
public record ScanGroup(String type, String region, String environment, String accountId, boolean providerOnly) {

    public static ScanGroup of(Watch w) {
        return w.providerOnly() ? new ScanGroup(w.type(), w.region(), null, null, true)
                : new ScanGroup(w.type(), w.region(), w.environment(), w.accountId(), false);
    }
}
