package com.iaf.platform.providers.domain;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the wizard proved about a provider before it is published: the connection tests per
 * environment and the "Try it" calls per action, as the platform recorded them (never as the
 * caller claims them). The review judges a provider file against this evidence. A store keeps the
 * newest entries of a provider only; {@code droppedConnections} and {@code droppedTries} count the
 * older ones it no longer has, so the review can say its judgement rests on what is left.
 */
public record ReviewEvidence(List<ConnectionTest> connections, List<ActionTry> tries, int droppedConnections, int droppedTries) {

    public static final ReviewEvidence NONE = new ReviewEvidence(List.of(), List.of());

    /**
     * One environment of a Test connection, with the endpoint and credential reference it used.
     * {@code team} is the team it was recorded for (null on a test recorded before teams were kept).
     */
    public record ConnectionTest(String environment, String endpoint, String credential, boolean ok, long durationMs, String message,
            Instant at, String by, String team) {

        /** A test recorded for no team. */
        public ConnectionTest(String environment, String endpoint, String credential, boolean ok, long durationMs, String message,
                Instant at, String by) {
            this(environment, endpoint, credential, ok, durationMs, message, at, by, null);
        }
    }

    /**
     * One Try it call. {@code response} is the real answer of a call that went through (null for a
     * failure); {@code status} is the HTTP status (0 when nothing answered). {@code endpoint} and
     * {@code credential} are the URL of the environment and the credential reference (never a
     * value) the call used; both are null on a try recorded before they were kept. {@code team} is
     * the team it was recorded for (null on a try recorded before teams were kept).
     */
    public record ActionTry(String action, String environment, boolean ok, int status, long durationMs, String errorClass,
            JsonNode response, Boolean verified, Instant at, String by, String endpoint, String credential, String team) {

        /** A try recorded for no team. */
        public ActionTry(String action, String environment, boolean ok, int status, long durationMs, String errorClass,
                JsonNode response, Boolean verified, Instant at, String by, String endpoint, String credential) {
            this(action, environment, ok, status, durationMs, errorClass, response, verified, at, by, endpoint, credential, null);
        }

        /** A try without the endpoint and credential it used. */
        public ActionTry(String action, String environment, boolean ok, int status, long durationMs, String errorClass,
                JsonNode response, Boolean verified, Instant at, String by) {
            this(action, environment, ok, status, durationMs, errorClass, response, verified, at, by, null, null, null);
        }
    }

    public ReviewEvidence {
        connections = connections == null ? List.of() : List.copyOf(connections);
        tries = tries == null ? List.of() : List.copyOf(tries);
        droppedConnections = Math.max(0, droppedConnections);
        droppedTries = Math.max(0, droppedTries);
    }

    /** Evidence of which nothing was dropped. */
    public ReviewEvidence(List<ConnectionTest> connections, List<ActionTry> tries) {
        this(connections, tries, 0, 0);
    }

    /**
     * The team drafting a provider that is not published yet: the team of the oldest entry that
     * names one. Only that team adds to the evidence after it, so no other team can push its
     * entries out of the window.
     */
    public Optional<String> claim() {
        Stream<Map.Entry<Instant, String>> all = Stream.concat(
                connections.stream().map(c -> new AbstractMap.SimpleEntry<>(c.at(), c.team())),
                tries.stream().map(t -> new AbstractMap.SimpleEntry<>(t.at(), t.team())));
        return all.filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .min(Map.Entry.comparingByKey(Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(Map.Entry::getValue);
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
