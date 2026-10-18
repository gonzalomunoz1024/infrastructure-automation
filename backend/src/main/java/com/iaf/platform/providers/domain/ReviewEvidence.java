package com.iaf.platform.providers.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the wizard proved about a provider before it is published: the connection tests per
 * environment and the "Try it" calls per action, as the platform recorded them (never as the
 * caller claims them). The review judges a provider file against this evidence.
 */
public record ReviewEvidence(List<ConnectionTest> connections, List<ActionTry> tries) {

    public static final ReviewEvidence NONE = new ReviewEvidence(List.of(), List.of());

    /** One environment of a Test connection, with the endpoint and credential reference it used. */
    public record ConnectionTest(String environment, String endpoint, String credential, boolean ok, long durationMs, String message,
            Instant at, String by) {
    }

    /**
     * One Try it call. {@code response} is the real answer of a call that went through (null for a
     * failure); {@code status} is the HTTP status (0 when nothing answered).
     */
    public record ActionTry(String action, String environment, boolean ok, int status, long durationMs, String errorClass,
            JsonNode response, Boolean verified, Instant at, String by) {
    }

    public ReviewEvidence {
        connections = connections == null ? List.of() : List.copyOf(connections);
        tries = tries == null ? List.of() : List.copyOf(tries);
    }

    /** The newest connection test of an environment. */
    public Optional<ConnectionTest> lastConnection(String environment) {
        return connections.stream().filter(c -> environment.equals(c.environment()))
                .max(Comparator.comparing(ConnectionTest::at, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    /** Tries of an action, oldest first; {@code environment} null means every environment. */
    public List<ActionTry> triesOf(String action, String environment) {
        return tries.stream()
                .filter(t -> action.equals(t.action()) && (environment == null || environment.equals(t.environment())))
                .sorted(Comparator.comparing(ActionTry::at, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The newest try of an action that went through, preferring {@code environment}. */
    public Optional<ActionTry> lastAnswer(String action, String environment) {
        List<ActionTry> here = triesOf(action, environment).stream().filter(t -> t.ok() && t.response() != null).toList();
        if (!here.isEmpty()) {
            return Optional.of(here.get(here.size() - 1));
        }
        List<ActionTry> anywhere = triesOf(action, null).stream().filter(t -> t.ok() && t.response() != null).toList();
        return anywhere.isEmpty() ? Optional.empty() : Optional.of(anywhere.get(anywhere.size() - 1));
    }
}
