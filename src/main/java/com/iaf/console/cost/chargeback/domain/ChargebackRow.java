package com.iaf.console.cost.chargeback.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One row of a chargeback ({@code ChargebackRow} of the contract): a cost centre's account's service, split by a tag's value when asked. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargebackRow(String costCentre, String accountId, String accountName, String service, Tag tag, double amount,
        @JsonIgnore long micros) {

    public record Tag(String key, String value) {
    }
}
