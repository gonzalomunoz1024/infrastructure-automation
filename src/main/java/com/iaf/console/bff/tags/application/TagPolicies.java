package com.iaf.console.bff.tags.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.iaf.console.bff.tags.domain.RequiredTag;
import com.iaf.console.bff.tags.domain.TagPolicy;
import com.iaf.console.bff.tags.ports.outbound.AccountDirectoryPort;

import reactor.core.publisher.Mono;

/**
 * The required tags for an account (WP 4.4): the organization's list from configuration, with
 * each default resolved for the account (its owning team from the identity service, its own
 * values from configuration). The owning team is kept for {@code account-ttl}; when the identity
 * service cannot say, the owner has no default and the create form asks for it.
 */
public class TagPolicies {

    static final String OWNING_TEAM = "account.owning-team";

    private static final Logger log = LoggerFactory.getLogger(TagPolicies.class);

    private final TagsProperties properties;
    private final AccountDirectoryPort accounts;
    private final Cache<String, Optional<String>> owningTeams;

    public TagPolicies(TagsProperties properties, AccountDirectoryPort accounts) {
        this.properties = properties;
        this.accounts = accounts;
        this.owningTeams = Caffeine.newBuilder().expireAfterWrite(properties.accountTtl()).maximumSize(100_000).build();
    }

    /** The account's policy, with its defaults resolved. */
    public Mono<TagPolicy> forAccount(String accountId) {
        return owningTeam(accountId).map(team -> policy(accountId, team.orElse(null)));
    }

    /** The policy with an owning team already known (or none). */
    public TagPolicy policy(String accountId, String owningTeam) {
        Map<String, String> own = properties.accounts().getOrDefault(accountId, Map.of());
        List<RequiredTag> tags = new ArrayList<>();
        for (TagsProperties.Tag t : properties.required()) {
            String value;
            String from;
            if (own.get(t.key()) != null) {
                value = own.get(t.key());
                from = RequiredTag.FROM_ACCOUNT;
            } else if (OWNING_TEAM.equals(t.defaultFrom()) && owningTeam != null) {
                value = owningTeam;
                from = RequiredTag.FROM_ACCOUNT;
            } else if (t.defaultFrom() != null && t.defaultFrom().startsWith(RequiredTag.FROM_INPUT)) {
                value = t.defaultValue();
                from = t.defaultFrom();
            } else {
                value = t.defaultValue();
                from = value == null ? null : RequiredTag.FROM_ORGANIZATION;
            }
            tags.add(new RequiredTag(t.key(), t.title(), t.description(), t.values(), t.pattern(), value, from));
        }
        return new TagPolicy(accountId, tags);
    }

    private Mono<Optional<String>> owningTeam(String accountId) {
        boolean needed = properties.required().stream().anyMatch(t -> OWNING_TEAM.equals(t.defaultFrom()));
        if (!needed) {
            return Mono.just(Optional.empty());
        }
        Optional<String> known = owningTeams.getIfPresent(accountId);
        if (known != null) {
            return Mono.just(known);
        }
        return accounts.owningTeam(accountId).map(Optional::of).defaultIfEmpty(Optional.empty())
                .doOnNext(team -> owningTeams.put(accountId, team))
                .onErrorResume(e -> {
                    log.warn("the owning team of account {} is unavailable, so the owner tag has no default: {}", accountId, e.getMessage());
                    return Mono.just(Optional.empty());
                });
    }
}
