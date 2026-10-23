package com.iaf.console.identity.authz.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Who a batch of checks is for: a user of an account, or a console service acting on its own. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Principal(String userId, String accountId, List<String> groups, String service) {

    public boolean isService() {
        return service != null && !service.isBlank() && (userId == null || userId.isBlank());
    }
}
