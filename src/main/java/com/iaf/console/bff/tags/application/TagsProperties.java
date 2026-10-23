package com.iaf.console.bff.tags.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * The organization's tag guardrails (WP 4.4). {@code required} lists the tags every create through
 * the console names, each with its title, allowed values or pattern, an organization-wide
 * {@code default}, and {@code default-from}: {@code account.owning-team} (the identity service's
 * account record), {@code account} (this file's {@code accounts.<accountId>.<key>}) or
 * {@code input.<field>} (the form copies the create's own field). {@code accounts} holds per-account
 * values, such as a cost centre, until the identity service's account record carries them.
 */
@ConfigurationProperties(prefix = "console.tags")
public record TagsProperties(List<Tag> required, Map<String, Map<String, String>> accounts, @DefaultValue("5m") Duration accountTtl) {

    public TagsProperties {
        required = required == null ? List.of() : List.copyOf(required);
        accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
    }

    /** One required tag as configured. */
    public record Tag(String key, String title, String description, List<String> values, String pattern, @Name("default") String defaultValue,
            String defaultFrom) {
    }
}
