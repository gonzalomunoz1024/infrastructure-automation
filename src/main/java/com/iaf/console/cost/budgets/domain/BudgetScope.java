package com.iaf.console.cost.budgets.domain;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.cost.ledger.domain.CostFilter;

/** What a budget counts: an account's resources, or only those with one tag value; a create may leave the account to the caller's. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetScope(String accountId, Tag tag) {

    public record Tag(String key, String value) {
        public Tag {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** The ledger filter of the scope. */
    public CostFilter filter() {
        return tag == null ? CostFilter.none() : CostFilter.none().withTag(tag.key(), tag.value());
    }

    /** Whether a new resource with these tags falls in the scope. */
    public boolean covers(java.util.Map<String, String> tags) {
        return tag == null || tag.value().equals(tags == null ? null : tags.get(tag.key()));
    }
}
