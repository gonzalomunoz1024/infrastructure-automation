package com.iaf.console.cost.auth.domain;

/**
 * What a console service calling as itself may reach here (hub decision 35). A person's token is
 * not limited by these: it prices, and it reads and changes its own account only.
 */
public enum RouteGroup {

    /** The price lists and estimates ({@code GET /v1/price-lists}, {@code POST /v1/cost:estimate}), which belong to no account. */
    PRICING,

    /**
     * Any account's data, the account named in the call: the cost reports (the organization view
     * among them), a resource's cost, the budgets, {@code POST /v1/budgets:check} and the chargeback.
     */
    ACCOUNTS,

    /** The ledger's operator calls: {@code POST /v1/ledger:sync}, {@code GET /v1/ledger/syncs/{runId}} and {@code GET /v1/ledger/status}. */
    LEDGER
}
