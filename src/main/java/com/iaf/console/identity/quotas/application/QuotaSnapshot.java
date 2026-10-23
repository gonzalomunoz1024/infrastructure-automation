package com.iaf.console.identity.quotas.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.iaf.console.identity.quotas.domain.Quota;
import com.iaf.console.identity.quotas.domain.QuotaRequest;

/**
 * What a {@code QuotaChanged} carries as its snapshot: the quota as served after the change and,
 * when the change is about an increase request (opened, decided, withdrawn), that request, so a
 * consumer can tell the requester without reading it back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuotaSnapshot(@JsonUnwrapped Quota quota, QuotaRequest request) {
}
