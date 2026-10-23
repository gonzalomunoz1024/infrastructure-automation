package com.iaf.console.bff.demo.directory.domain;

import java.util.List;

/** A person of the demo: their identity-provider subject, name, email and IdP groups. */
public record DemoPerson(String subject, String name, String email, List<String> idpGroups) {

    public DemoPerson {
        idpGroups = idpGroups == null ? List.of() : List.copyOf(idpGroups);
    }

    /** The console user id the identity stand-in gives them. */
    public String userId() {
        return "user_" + subject;
    }
}
