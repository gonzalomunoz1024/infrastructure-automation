package com.iaf.console.bff.recent.domain;

import com.iaf.console.wfrn.Wfrn;

/**
 * What a recent page or a favourite is shown on: the person may still list its type,
 * {@code <service>:list} on every resource of the type in the account and region (the wfrn's
 * region for a resource, the session's for a type).
 */
public record ListCheck(String service, String region, String type) {

    public String action() {
        return service + ":list";
    }

    /** {@code wfrn:<partition>:<service>:<region>:<account>:<type>/*}. */
    public String resource(String partition, String accountId) {
        return "wfrn:" + partition + ":" + service + ":" + region + ":" + accountId + ":" + type + "/*";
    }

    /** A type key's check ({@code compute/vm}) in a region. */
    public static ListCheck ofTypeKey(String typeKey, String region) {
        int slash = typeKey.indexOf('/');
        return new ListCheck(typeKey.substring(0, slash), region, typeKey.substring(slash + 1));
    }

    public static ListCheck ofWfrn(Wfrn wfrn) {
        return new ListCheck(wfrn.service(), wfrn.region(), wfrn.type());
    }
}
