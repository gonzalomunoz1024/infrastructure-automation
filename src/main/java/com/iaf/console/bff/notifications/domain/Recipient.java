package com.iaf.console.bff.notifications.domain;

import java.util.Objects;

/** A person a notification is for: their console user id, their identity-provider address (may be absent) and their name. */
public record Recipient(String userId, String email, String name) {

    /** The prefix of every console user id; anything else is not a person the console knows. */
    public static final String USER_PREFIX = "user_";

    public Recipient {
        Objects.requireNonNull(userId, "userId");
        email = email == null || email.isBlank() ? null : email.strip();
    }

    public boolean hasAddress() {
        return email != null;
    }

    /** What to call the person in a message: their name, else their address, else their id. */
    public String displayName() {
        return name != null && !name.isBlank() ? name : email != null ? email : userId;
    }

    public static boolean isConsoleUser(String userId) {
        return userId != null && userId.startsWith(USER_PREFIX) && userId.length() > USER_PREFIX.length();
    }
}
