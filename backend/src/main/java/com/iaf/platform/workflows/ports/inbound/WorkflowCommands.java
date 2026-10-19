package com.iaf.platform.workflows.ports.inbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.workflows.domain.ReplayResult;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

/**
 * Write side of the workflows slice: drafts, validation, publishing through change requests,
 * imports, and the YAML round trip the browser uses instead of parsing YAML itself.
 */
public interface WorkflowCommands {

    /**
     * A workflow document, as YAML text or as the JSON document {@link WorkflowYaml#toJson}
     * produces (the YAML structure, not the Java record shape). One of the two is required.
     */
    record WorkflowSource(String yaml, JsonNode definition) {
        public static WorkflowSource ofYaml(String yaml) {
            return new WorkflowSource(yaml, null);
        }

        public static WorkflowSource ofDefinition(JsonNode definition) {
            return new WorkflowSource(null, definition);
        }

        /** Parse whichever was given; an {@link IllegalArgumentException} (400) when neither or when the structure is broken. */
        public WorkflowDefinition parse() {
            if (yaml != null && !yaml.isBlank()) {
                return WorkflowYaml.parse(yaml);
            }
            if (definition != null && !definition.isNull()) {
                return WorkflowYaml.fromJson(definition);
            }
            throw new IllegalArgumentException("yaml or definition is required");
        }

        public boolean hasYaml() {
            return yaml != null && !yaml.isBlank();
        }
    }

    /**
     * Body of {@code POST /workflows:dry-run}: the document plus the job input to walk it with.
     * {@code providers} (optional): provider files that are not published yet (drafts), each
     * checked as a provider publish checks it, which stand in for the published provider of
     * their name in this dry run only.
     */
    record DryRunRequest(String yaml, JsonNode definition, JsonNode input, List<String> providers) {
        public DryRunRequest(String yaml, JsonNode definition, JsonNode input) {
            this(yaml, definition, input, null);
        }

        public WorkflowSource source() {
            return new WorkflowSource(yaml, definition);
        }
    }

    /** Body of {@code POST /workflows:validate}: the document, and drafts of providers as a dry run takes them, which the checks see for this call only. */
    record ValidateRequest(String yaml, JsonNode definition, List<String> providers) {
        public WorkflowSource source() {
            return new WorkflowSource(yaml, definition);
        }
    }

    /**
     * Body of {@code POST /workflows/{name}:publish}. {@code strategy} is canary or all; null means
     * the file's rollout strategy. {@code requires} (optional): ids of provider change requests,
     * open or merged, that the workflow's change request needs merged first.
     */
    record PublishWorkflow(String strategy, String notes, List<String> requires) {
        public PublishWorkflow(String strategy, String notes) {
            this(strategy, notes, null);
        }
    }

    /** Body of {@code POST /workflows:import}: {@code source} is idp, ansible, terraform or openapi. */
    record ImportRequest(String source, String ref) {
    }

    record DraftSaved(String name, String version, List<Problem> problems) {
    }

    record Validation(boolean valid, List<Problem> problems) {
    }

    record Imported(String yaml, List<String> notes) {
    }

    record Rendered(String yaml) {
    }

    /** {@code definition} is null when the YAML does not parse; the problem says why. */
    record Parsed(JsonNode definition, List<Problem> problems) {
    }

    /** Creates a draft; the name comes from {@code metadata.name}. 409 when the workflow exists. */
    Mono<CommandAccepted> createDraft(WorkflowSource source);

    /** Upserts the one draft of a workflow (created when the workflow does not exist yet). */
    Mono<DraftSaved> saveDraft(String name, WorkflowSource source);

    /** Deletes the draft version(s) of a workflow; published versions are never deleted (409). */
    Mono<Void> deleteDraft(String name);

    /** Every check of a document as the backend runs them, with the drafts of providers it carries; an invalid draft is a 400 with its problems. */
    Mono<Validation> validate(ValidateRequest request);

    /** {@link #validate(ValidateRequest)} of a document that carries no draft. */
    default Mono<Validation> validate(WorkflowSource source) {
        return validate(new ValidateRequest(source.yaml(), source.definition(), null));
    }

    /** Walks the document against the samples, with the drafts of providers it carries; an invalid draft is a 400 with its problems. */
    Mono<DryRunResult> dryRun(DryRunRequest request);

    /**
     * Opens a change request for the draft; the accepted id is the change request id. With
     * {@code requires} its checks and replay also see the provider files of those change
     * requests, and it merges only once every one of them is merged.
     */
    Mono<CommandAccepted> publish(String name, PublishWorkflow command);

    /** Merges an open change request whose checks passed; a workflow one waits for every change request it requires to be merged (409). */
    Mono<CommandAccepted> mergeChangeRequest(String id);

    Mono<CommandAccepted> closeChangeRequest(String id);

    Mono<ReplayResult> replayCheck(String name, String version);

    Mono<Imported> importFrom(ImportRequest request);

    /** {@code definition} -> canonical YAML. */
    Mono<Rendered> render(WorkflowSource source);

    /** YAML -> {@code definition} plus the problems the checks find. */
    Mono<Parsed> parse(WorkflowSource source);
}
