package com.iaf.console.cost.budgets.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A monthly budget of an account or of one tag in it: the amount (micros), the percentages that
 * alert, whether being over refuses creates in its scope, and its version for {@code If-Match}.
 * The idempotency key and the hash of the body that created it answer a retried create.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Budget(String budgetId, String name, BudgetScope scope, long amountMicros, String currency, List<Integer> thresholds,
        boolean blockCreates, long version, String createdBy, Instant createdAt, Instant updatedAt, String idempotencyKey, String requestHash) {

    public static final String ID_PREFIX = "bud_";

    public Budget {
        Objects.requireNonNull(budgetId, "budgetId");
        Objects.requireNonNull(scope, "scope");
        thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
    }

    public String accountId() {
        return scope.accountId();
    }

    /** The ETag of this version. */
    public String etag() {
        return "\"" + version + "\"";
    }

    public Budget replaced(BudgetInput.Valid input, Instant at) {
        return new Budget(budgetId, input.name(), scope, input.amountMicros(), input.currency(), input.thresholds(), input.blockCreates(), version + 1,
                createdBy, createdAt, at, idempotencyKey, requestHash);
    }
}
