package com.iaf.platform.providers.ports.inbound;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.providers.domain.ConnectionResult;
import com.iaf.platform.providers.domain.ProviderDefinition.GitRef;
import com.iaf.platform.providers.domain.ProviderReview;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.domain.TryResult;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

import reactor.core.publisher.Mono;

/**
 * Write side of the providers slice: the Add provider wizard's steps (import a spec, test the
 * connection, try an action, validate, publish) and the admin edits (refresh the spec, overrides,
 * SLO), which change a published provider through a change request like a publish does.
 */
public interface ProviderCommands {

    /**
     * {@code type} is openapi; one of {@code url}, {@code content} or {@code git} (a file in a
     * repository at a branch, tag or commit; a private one names a {@code vault:} token) carries the document.
     */
    record ImportSpec(String type, String url, String content, GitRef git) {
        public ImportSpec(String type, String url, String content) {
            this(type, url, content, null);
        }
    }

    /**
     * Read the provider's spec again from where its file says it is. {@code ref} reads a Git spec
     * at another branch, tag or commit (default: the file's ref); {@code content} is the new
     * document of a pasted spec.
     */
    record RefreshSpec(String ref, String content) {
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

    /**
     * {@code strategy} is canary or all (default canary: a new version takes a share of the jobs'
     * calls until the release's gates promote it; the first version of a provider takes them all).
     */
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

    /**
     * Opens a change request that moves the provider to its spec's newest content (status
     * {@code open}), or answers {@code unchanged} when the document is the one already pinned.
     */
    Mono<CommandAccepted> refreshSpec(String name, RefreshSpec command);

    /** Opens a change request for the next patch version with the override ({@code unchanged} when it adds nothing). */
    Mono<CommandAccepted> overrides(String name, SetOverride command);

    /** Opens a change request for the next patch version with the SLO and alert channel ({@code unchanged} when they are the same). */
    Mono<CommandAccepted> slo(String name, SetSlo command);
}
