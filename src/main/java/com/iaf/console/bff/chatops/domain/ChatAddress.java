package com.iaf.console.bff.chatops.domain;

import java.util.Objects;

/**
 * Where a message can be posted without a request to answer: a Slack channel or user (the bot
 * token posts there), or a Teams conversation at the connector the Bot Framework named
 * ({@code serviceUrl}, Teams only).
 */
public record ChatAddress(ChatPlatform platform, String workspaceId, String conversationId, String serviceUrl) {

    public ChatAddress {
        Objects.requireNonNull(platform, "platform");
        workspaceId = ChatIdentity.requireText(workspaceId, "workspaceId");
        conversationId = ChatIdentity.requireText(conversationId, "conversationId");
        serviceUrl = serviceUrl == null || serviceUrl.isBlank() ? null : serviceUrl.strip();
        if (platform == ChatPlatform.TEAMS && serviceUrl == null) {
            throw new IllegalArgumentException("a Teams conversation needs its serviceUrl");
        }
    }

    /** The key a channel's subscription is kept under. */
    public String key() {
        return platform.id() + ":" + workspaceId + ":" + conversationId;
    }
}
