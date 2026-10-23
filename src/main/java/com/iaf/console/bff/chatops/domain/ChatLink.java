package com.iaf.console.bff.chatops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.iaf.console.wfrn.Ulid;

/**
 * A chat identity linked to a console person (decision 31.3): who on the chat platform, who in the
 * console (the provider's issuer and subject, address, name and groups as the person's browser
 * session had them, so the identity service can resolve the person again), when, the account and
 * region chat commands act in, and where the person can be messaged directly ({@code direct},
 * absent when the platform gave no conversation).
 */
public record ChatLink(
        String id,
        ChatIdentity identity,
        String userId,
        String issuer,
        String subject,
        String email,
        String name,
        List<String> groups,
        Instant linkedAt,
        String accountId,
        String region,
        ChatAddress direct) {

    public static final String ID_PREFIX = "chl_";
    private static final Pattern ID = Pattern.compile("^chl_[0-9a-z]{26}$");

    public ChatLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(linkedAt, "linkedAt");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(region, "region");
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public static String newId() {
        return ID_PREFIX + Ulid.next().toLowerCase();
    }

    public static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public ChatPlatform platform() {
        return identity.platform();
    }

    /** The same link acting in another account and region. */
    public ChatLink actingIn(String otherAccountId, String otherRegion) {
        return new ChatLink(id, identity, userId, issuer, subject, email, name, groups, linkedAt, otherAccountId, otherRegion, direct);
    }
}
