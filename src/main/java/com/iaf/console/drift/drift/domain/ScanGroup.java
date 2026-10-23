package com.iaf.console.drift.drift.domain;

/**
 * The unit of a paged read: one type, region, environment and account, what {@code :observe-list}
 * takes. Resources imported from discovery form their own groups, without an environment: they are
 * read from the provider's discover pages of every environment of the region.
 */
public record ScanGroup(String type, String region, String environment, String accountId, boolean providerOnly) {

    public static ScanGroup of(Watch w) {
        return new ScanGroup(w.type(), w.region(), w.providerOnly() ? null : w.environment(), w.accountId(), w.providerOnly());
    }
}
