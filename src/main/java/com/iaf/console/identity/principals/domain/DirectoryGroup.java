package com.iaf.console.identity.principals.domain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** A group of the identity provider's directory, by name, with its members as the directory knows them. */
public record DirectoryGroup(String name, List<Member> members) {

    public DirectoryGroup {
        Objects.requireNonNull(name, "name");
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** True when a member has this email, compared lowercased. */
    public boolean hasEmail(String email) {
        if (email == null) {
            return false;
        }
        String wanted = email.toLowerCase(Locale.ROOT);
        return members.stream().anyMatch(m -> m.email() != null && m.email().toLowerCase(Locale.ROOT).equals(wanted));
    }

    /** A member: their email, and their subject at the identity provider when the directory knows it. */
    public record Member(String email, String subject) {

        public Member {
            Objects.requireNonNull(email, "email");
        }
    }
}
