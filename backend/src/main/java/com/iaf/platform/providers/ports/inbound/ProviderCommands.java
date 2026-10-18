package com.iaf.platform.providers.ports.inbound;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.domain.ConnectionResult;
import com.iaf.platform.providers.domain.ProviderReview;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.domain.TryResult;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

import reactor.core.publisher.Mono;

/**
 * Write side of the providers slice: the Add provider wizard's steps (import a spec, test the
 * connection, try an action, validate, publish) and the admin edits (overrides, SLO).
 */
public interface ProviderCommands {

    /** {@code type} is openapi; one of {@code url} or {@code content} carries the document. */
    record ImportSpec(String type, String url, String content) {
    }

    /**
     * {@code auth} is the provider file's auth block: {@code { type, credential, header }}. With
     * {@code provider} (the provider's name) the results are recorded as review evidence.
     */
    record TestConnection(Map<String, String> endpoints, JsonNode auth, String provider) {
        public TestConnection(Map<String, String> endpoints, JsonNode auth) {
            this(endpoints, auth, null);
        }
    }

    record TryAction(String yaml, String action, String environment, JsonNode input) {
    }

    record Validate(String yaml) {
    }

    /**
     * {@code checks} and {@code problems} judge the file ({@code valid} is about them alone);
     * {@code review} judges it against what the wizard proved and gates the publish.
     */
    record ValidationResult(List<Check> checks, List<Problem> problems, ProviderReview.Result review) {
        public ValidationResult(List<Check> checks, List<Problem> problems) {
            this(checks, problems, null);
        }

        public boolean valid() {
            return problems.stream().noneMatch(Problem::isError);
        }
    }

    /** {@code strategy} is canary or all (default all: a provider has no traffic to split). */
    record Publish(String yaml, String strategy) {
    }

    record Draft(String description) {
    }

    /** "Use the real response": the schema inferred from {@code response} overrides the action's output schema. */
    record SetOverride(String action, JsonNode response) {
    }

    record SetSlo(Double successRate, Integer p95Ms, String channel) {
    }

    Mono<SpecImport> importSpec(ImportSpec command);

    Mono<ConnectionResult.Results> testConnection(TestConnection command);

    Mono<TryResult> tryAction(TryAction command);

    Mono<ValidationResult> validate(Validate command);

    /** Opens a change request; the id returned is the change request's. Merging it publishes the provider. */
    Mono<CommandAccepted> publish(Publish command);

    /** "Ask Devin" in the wizard. */
    Mono<ProviderDraft> draft(Draft command);

    Mono<CommandAccepted> overrides(String name, SetOverride command);

    Mono<CommandAccepted> slo(String name, SetSlo command);
}
