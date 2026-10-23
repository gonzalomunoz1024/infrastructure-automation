package com.iaf.console.fake.identity.domain;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The console user a call acts for (P-02), taken from a verified X-On-Behalf-Of assertion. */
public record Delegation(String userId, String name, String email, List<String> groups, String accountId, String region, String assertionId) {

    public ObjectNode document() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("userId", userId);
        if (name != null) {
            out.put("name", name);
        }
        if (email != null) {
            out.put("email", email);
        }
        out.put("accountId", accountId);
        if (region != null) {
            out.put("region", region);
        }
        out.put("assertionId", assertionId);
        return out;
    }

    /** The Person document of this user, as jobs and events name it. */
    public ObjectNode person() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", userId);
        if (name != null) {
            out.put("name", name);
        }
        if (email != null) {
            out.put("email", email);
        }
        return out;
    }
}
