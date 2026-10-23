package com.iaf.console.identity.iam.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The actions that exist: per service, its verbs. Built from the platform's resource types and
 * catalog (every type's service gets the console's read verbs and the verbs of the workflows on
 * it) plus the console's own services. A policy may name any of them, or a wildcard. It also
 * keeps the platform's resource type names, which the {@code resources.<type>} quotas follow.
 */
public record ActionCatalog(Map<String, Set<String>> verbsByService, Set<String> resourceTypes) {

    /** The verbs every service has in the console. */
    public static final Set<String> READ_VERBS = Set.of("get", "list", "observe", "history");

    /** The console's own services and their verbs, independent of the platform. */
    public static final Map<String, Set<String>> CONSOLE_SERVICES = Map.of(
            "iam", Set.of("get", "list", "create", "update", "delete", "manage"),
            "account", Set.of("get", "list", "update"),
            "quota", Set.of("get", "list", "set", "request-increase"),
            "audit", Set.of("get", "list", "export"),
            "cost", Set.of("get", "list", "export"),
            "budgets", Set.of("get", "list", "create", "update", "delete"),
            "search", Set.of("query"),
            "access-request", Set.of("get", "list", "create", "decide"),
            "access-review", Set.of("get", "list", "create", "decide", "cancel"));

    public ActionCatalog {
        Map<String, Set<String>> copy = new TreeMap<>();
        verbsByService.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        verbsByService = Map.copyOf(copy);
        resourceTypes = resourceTypes == null ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(resourceTypes));
    }

    /** A catalog of the console's own services only, before the platform answered. */
    public static ActionCatalog consoleOnly() {
        return new ActionCatalog(CONSOLE_SERVICES, Set.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether a non-wildcard action is known; wildcards in either part are accepted when the other part is known or a wildcard. */
    public boolean knows(String action) {
        String service = Actions.service(action);
        String verb = Actions.verb(action);
        if (Actions.hasWildcard(service)) {
            return true;
        }
        Set<String> verbs = verbsByService.get(service);
        if (verbs == null) {
            return false;
        }
        return Actions.hasWildcard(verb) || verbs.contains(verb);
    }

    public Set<String> services() {
        return verbsByService.keySet();
    }

    public static final class Builder {

        private final Map<String, Set<String>> verbs = new TreeMap<>();
        private final Set<String> types = new TreeSet<>();

        private Builder() {
            CONSOLE_SERVICES.forEach((k, v) -> verbs.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
        }

        public Builder service(String service) {
            if (service != null && !service.isBlank()) {
                verbs.computeIfAbsent(service, x -> new TreeSet<>()).addAll(READ_VERBS);
            }
            return this;
        }

        /** A resource type of the platform, by its name. */
        public Builder type(String name) {
            if (name != null && !name.isBlank()) {
                types.add(name);
            }
            return this;
        }

        public Builder verb(String service, String verb) {
            if (service != null && !service.isBlank() && verb != null && !verb.isBlank()) {
                verbs.computeIfAbsent(service, x -> new TreeSet<>(READ_VERBS)).add(verb);
            }
            return this;
        }

        /** The catalog: the services given plus the console's own, whatever the platform answered. */
        public ActionCatalog build() {
            CONSOLE_SERVICES.forEach((service, v) -> verbs.computeIfAbsent(service, x -> new TreeSet<>()).addAll(v));
            return new ActionCatalog(verbs, types);
        }
    }
}
