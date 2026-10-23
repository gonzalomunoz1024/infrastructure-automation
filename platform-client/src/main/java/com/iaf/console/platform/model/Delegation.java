package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The console user a call acts for, taken from a verified X-On-Behalf-Of assertion. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Delegation(String userId, String name, String email, String accountId, String region, String assertionId) {
}
