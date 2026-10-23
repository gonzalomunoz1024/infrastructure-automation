package com.iaf.console.bff.chatops.domain;

import java.util.Objects;

/**
 * Who someone is on a chat platform: the platform, the workspace (a Slack team, a Teams tenant),
 * the chat user's own id there and the name the platform showed.
 */
public record ChatIdentity(ChatPlatform platform, String workspaceId, String chatUserId, String chatUserName) {

    public ChatIdentity {
        Objects.requireNonNull(platform, "platform");
        workspaceId = requireText(workspaceId, "workspaceId");
        chatUserId = requireText(chatUserId, "chatUserId");
        chatUserName = chatUserName == null || chatUserName.isBlank() ? chatUserId : chatUserName.strip();
    }

    /** The key one link per chat user is kept under: platform, workspace and user. */
    public String key() {
        return platform.id() + ":" + workspaceId + ":" + chatUserId;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " is 1 to 256 characters");
        }
        return value.strip();
    }
}
