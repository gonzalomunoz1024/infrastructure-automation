package com.iaf.console.cost.ledger.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Where an account's ledger stands: the first day it holds, the last day closed, the earliest day a
 * late delta reopened ({@code dirtyFrom}), and a revision every late delta bumps, so a close that
 * raced one does not clear the mark.
 */
public record AccountLedger(String accountId, LocalDate firstDay, LocalDate closedThrough, LocalDate dirtyFrom, long rev) {

    public AccountLedger {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(firstDay, "firstDay");
        Objects.requireNonNull(closedThrough, "closedThrough");
    }

    /** A new ledger whose first day is {@code firstDay}: nothing closed yet. */
    public static AccountLedger start(String accountId, LocalDate firstDay) {
        return new AccountLedger(accountId, firstDay, firstDay.minusDays(1), null, 0);
    }

    /** The first day a close must (re)compute. */
    public LocalDate nextToClose() {
        LocalDate next = closedThrough.plusDays(1);
        if (dirtyFrom != null && dirtyFrom.isBefore(next)) {
            return dirtyFrom.isBefore(firstDay) ? firstDay : dirtyFrom;
        }
        return next;
    }
}
