package com.iaf.console.identity.iam.domain;

/**
 * The wfrns of IAM things, the resources the IAM writes are checked against:
 * {@code wfrn:<partition>:iam::<account>:<kind>/<id>} (no region: identity is region-less).
 */
public final class IamResources {

    private IamResources() {
    }

    public static String of(String partition, String accountId, String kind, String id) {
        return "wfrn:" + partition + ":iam::" + accountId + ":" + kind + "/" + id;
    }

    /** Every resource of a kind in the account, for a create or a list. */
    public static String any(String partition, String accountId, String kind) {
        return of(partition, accountId, kind, "*");
    }

    /** Every resource of every service in the account: the managed policies' resource. */
    public static String everything(String partition, String accountId) {
        return "wfrn:" + partition + ":*:*:" + accountId + ":*";
    }
}
