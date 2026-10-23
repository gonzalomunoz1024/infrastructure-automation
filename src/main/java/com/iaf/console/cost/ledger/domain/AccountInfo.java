package com.iaf.console.cost.ledger.domain;

import java.util.Objects;

/** An account as reports name it: its id, name, owning team and cost centre (the owning team when it has none). */
public record AccountInfo(String accountId, String name, String owningTeam, String costCentre) {

    public AccountInfo {
        Objects.requireNonNull(accountId, "accountId");
    }

    public static AccountInfo unknown(String accountId) {
        return new AccountInfo(accountId, accountId, null, null);
    }

    /** The cost centre the account is charged to: its own, else its owning team, else "unassigned". */
    public String chargedTo() {
        if (costCentre != null && !costCentre.isBlank()) {
            return costCentre;
        }
        return owningTeam != null && !owningTeam.isBlank() ? owningTeam : "unassigned";
    }
}
