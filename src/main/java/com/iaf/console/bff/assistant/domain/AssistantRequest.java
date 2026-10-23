package com.iaf.console.bff.assistant.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Everything an assistant gets for one turn, gathered by the BFF with the person's own session:
 * the question and where the person is, the account and region, the types the person may list or
 * create (with the create form and the verbs they may run), the resources the question names, the
 * resource to explain with its facts or the failed job to read, and the conversation so far. No
 * session, token or key is in it, and nothing in it lets the assistant call the console.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantRequest(
        String conversationId,
        String turnId,
        String intent,
        String message,
        AskContext context,
        Account account,
        List<TypeInfo> types,
        List<ResourceInfo> resources,
        Subject subject,
        Failure failure,
        List<HistoryTurn> history) {

    /** A question in words. */
    public static final String ASK = AssistantAsk.ASK;
    /** Explain the resource of {@code subject}. */
    public static final String EXPLAIN = AssistantAsk.EXPLAIN;
    /** Read the failed job of {@code failure}: its cause and a fix to try (the failure explainer, WP 9.2). */
    public static final String FAILURE = "failure";

    public AssistantRequest {
        types = types == null ? List.of() : List.copyOf(types);
        resources = resources == null ? List.of() : List.copyOf(resources);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** The session's account and region, and the managed roles the person holds there. */
    public record Account(String accountId, String name, String region, List<String> roles) {
        public Account {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /**
     * A type of the region's catalogue the person may list or create: its names, whether they may
     * list it and create one ({@code <service>:list} on the type's pattern, {@code <service>:create}),
     * the create form without its secret fields, and the verbs they may run on the type's resources.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TypeInfo(String type, String service, String title, String plural, boolean list, boolean create, JsonNode createInput,
            List<VerbInfo> verbs) {
        public TypeInfo {
            verbs = verbs == null ? List.of() : List.copyOf(verbs);
        }

        public Optional<VerbInfo> verb(String name) {
            return verbs.stream().filter(v -> v.verb().equals(name)).findFirst();
        }
    }

    /** A verb of a type the person may run, with its form (the subject left out) without secret fields. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerbInfo(String verb, String title, boolean destructive, JsonNode input) {
    }

    /** A resource the question names, as its list row has it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceInfo(String wfrn, String type, String name, String state, Map<String, String> tags) {
        public ResourceInfo {
            tags = copy(tags);
        }
    }

    /**
     * The resource an {@code explain} turn explains, as the person may read it: its detail (state,
     * tags, attributes without secret-looking fields, its last error), who created it and when,
     * its last change, what depends on it (counted in every account, named in this one), its open
     * drift finding, and its monthly cost when the cost service answered one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subject(
            String wfrn,
            String type,
            String service,
            String name,
            String region,
            String state,
            Map<String, String> tags,
            JsonNode attributes,
            JsonNode lastError,
            Instant createdAt,
            String createdBy,
            Instant lastChangeAt,
            String lastChangeBy,
            Long dependents,
            List<ResourceInfo> namedDependents,
            Boolean drift,
            JsonNode driftFields,
            Double monthlyCost,
            String currency) {

        public Subject {
            tags = copy(tags);
            namedDependents = namedDependents == null ? List.of() : List.copyOf(namedDependents);
            currency = monthlyCost == null ? null : currency;
        }

        /** The facts an explanation carries, from what the BFF read, never from the assistant. */
        public ResourceExplanation.Facts facts() {
            return new ResourceExplanation.Facts(monthlyCost, currency, createdAt, createdBy, lastChangeAt, lastChangeBy, dependents, drift);
        }
    }

    /**
     * The failed job a {@code failure} turn reads, as the platform reports it to the person: the
     * workflow, the error class and message, the failing step, and the job's input without secret
     * fields with the workflow's form, so a fix names fields the retry takes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failure(String jobId, String workflow, String title, String status, String errorClass, String message, String stepId, String stepName,
            Integer attempt, boolean paused, Map<String, Object> input, JsonNode inputForm) {
        public Failure {
            input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }
    }

    /** An earlier turn, in words. */
    public record HistoryTurn(String role, String text) {
    }

    /** The type of this name, when the person may list or create it. */
    public Optional<TypeInfo> type(String name) {
        return name == null ? Optional.empty() : types.stream().filter(t -> t.type().equals(name)).findFirst();
    }

    public boolean explains() {
        return EXPLAIN.equals(intent);
    }

    /** Tags as an unmodifiable map in their order, a null value read as "" (has the tag). */
    static Map<String, String> copy(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        tags.forEach((k, v) -> {
            if (k != null) {
                copy.put(k, v == null ? "" : v);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
