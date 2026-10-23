package com.iaf.console.bff.chatops.domain;

import java.util.Locale;
import java.util.Optional;

/** The chat platforms ChatOps answers on (decision 31.3), named as the API and the notification channels name them. */
public enum ChatPlatform {

    SLACK("slack"),
    TEAMS("teams");

    private final String id;

    ChatPlatform(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The platform of a name ({@code slack}, {@code teams}, in any case); empty for anything else. */
    public static Optional<ChatPlatform> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (ChatPlatform p : values()) {
            if (p.id.equals(wanted)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
