package com.iaf.console.bff.chatops.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A chat channel an account admin subscribed to an account's alerts (decision 31.3): the account,
 * the channel, the notification kinds it gets ({@code *} for every kind), who subscribed it and when.
 * One subscription per account and channel.
 */
public record ChannelSubscription(String accountId, ChatAddress channel, Set<String> kinds, String subscribedBy, Instant updatedAt) {

    /** Every kind. */
    public static final String ALL = "*";
    private static final Pattern KIND = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public ChannelSubscription {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(updatedAt, "updatedAt");
        kinds = Set.copyOf(new TreeSet<>(kinds == null ? Set.of() : kinds));
    }

    /** Whether a kind is one a subscription can name: {@code *}, or lowercase words joined by dots or hyphens. */
    public static boolean isKind(String kind) {
        return ALL.equals(kind) || (kind != null && KIND.matcher(kind).matches());
    }

    public boolean covers(String kind) {
        return kinds.contains(ALL) || kinds.contains(kind);
    }

    /** The key it is kept under: the account and the channel. */
    public String key() {
        return accountId + "/" + channel.key();
    }

    public ChannelSubscription with(Set<String> added, String by, Instant at) {
        Set<String> next = new TreeSet<>(kinds);
        next.addAll(added);
        return new ChannelSubscription(accountId, channel, next, by, at);
    }

    public ChannelSubscription without(Set<String> removed, String by, Instant at) {
        Set<String> next = new TreeSet<>(kinds);
        next.removeAll(removed);
        return new ChannelSubscription(accountId, channel, next, by, at);
    }
}
