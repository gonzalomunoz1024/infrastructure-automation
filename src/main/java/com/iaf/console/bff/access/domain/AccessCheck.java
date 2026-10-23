package com.iaf.console.bff.access.domain;

import java.util.Objects;

/** One question for the policy engine: may the principal do {@code action} on {@code resource}? */
public record AccessCheck(String action, String resource) {

    public AccessCheck {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        if (!action.contains(":")) {
            throw new IllegalArgumentException("an action is <service>:<verb>, was '" + action + "'");
        }
    }

    /** The action of a verb of a service: {@code compute:create}. */
    public static String action(String service, String verb) {
        return service + ":" + verb;
    }

    /** The wfrn pattern of every resource of a type in an account and region: {@code wfrn:wf:compute:global:123456789012:vm/*}. */
    public static String typePattern(String partition, String service, String region, String accountId, String type) {
        return "wfrn:" + partition + ":" + service + ":" + region + ":" + accountId + ":" + type + "/*";
    }

    /**
     * The wfrn pattern of every record of a kind of the console's own IAM in an account, as the
     * identity service names them (no region): {@code wfrn:wf:iam::123456789012:user/*}.
     */
    public static String iamPattern(String partition, String accountId, String kind) {
        return "wfrn:" + partition + ":iam::" + accountId + ":" + kind + "/*";
    }

    /** The wfrn pattern of everything of a service in an account and region. */
    public static String servicePattern(String partition, String service, String region, String accountId) {
        return "wfrn:" + partition + ":" + service + ":" + region + ":" + accountId + ":*";
    }
}
