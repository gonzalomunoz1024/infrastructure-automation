package com.iaf.console.bff.demo.identity.application;

import java.util.List;

/** Who calls the demo identity stand-in: the console itself ({@code svc} with no account) or a user in an account. */
public record DemoCaller(String subject, String accountId, String service, List<String> groups) {

    public DemoCaller {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public boolean internal() {
        return accountId == null && service != null;
    }

    /** The person's IdP subject behind a console user id ({@code user_<subject>}). */
    public String personSubject() {
        return subject != null && subject.startsWith("user_") ? subject.substring(5) : subject;
    }
}
