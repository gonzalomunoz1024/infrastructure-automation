package com.iaf.console.platform.client;

/** The filters of an evidence listing: workflow, a time range, the requester, the account and a wfrn. */
public record AuditFilter(String workflow, String from, String to, String requester, String account, String wfrn) {

    private static final AuditFilter ALL = new AuditFilter(null, null, null, null, null, null);

    public static AuditFilter all() {
        return ALL;
    }

    public static AuditFilter ofAccount(String accountId) {
        return new AuditFilter(null, null, null, null, accountId, null);
    }

    public AuditFilter withWorkflow(String w) {
        return new AuditFilter(w, from, to, requester, account, wfrn);
    }

    public AuditFilter withRange(String f, String t) {
        return new AuditFilter(workflow, f, t, requester, account, wfrn);
    }

    public AuditFilter withRequester(String r) {
        return new AuditFilter(workflow, from, to, r, account, wfrn);
    }

    public AuditFilter withWfrn(String w) {
        return new AuditFilter(workflow, from, to, requester, account, w);
    }
}
