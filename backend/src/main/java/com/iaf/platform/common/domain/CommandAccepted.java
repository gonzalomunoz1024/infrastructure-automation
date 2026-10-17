package com.iaf.platform.common.domain;

import java.util.Map;

/** What every command returns: the id of what it created or changed, and where it stands. */
public record CommandAccepted(String id, String status, Map<String, String> links) {

    public static CommandAccepted of(String id, String status) {
        return new CommandAccepted(id, status, null);
    }

    public static CommandAccepted of(String id, String status, Map<String, String> links) {
        return new CommandAccepted(id, status, links);
    }
}
