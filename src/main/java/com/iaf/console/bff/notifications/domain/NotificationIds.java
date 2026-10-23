package com.iaf.console.bff.notifications.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The names that make a notification happen once: its id, the same on every replica and channel
 * for one event and person, and the key of one delivery, {@code <eventKey>:<userId>:<channel>},
 * whose first insert wins (decision 32.2). The event's key is its deterministic key, never its
 * random id.
 */
public final class NotificationIds {

    public static final String PREFIX = "ntf_";

    private NotificationIds() {
    }

    /** {@code ntf_} and 32 hex digits of SHA-256 over {@code <eventKey>:<userId>}. */
    public static String of(String eventKey, String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((eventKey + ":" + userId).getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    public static String deliveryKey(String eventKey, String userId, String channel) {
        return eventKey + ":" + userId + ":" + channel;
    }
}
