package com.iaf.platform.workflows.ports.inbound;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.CheckContext;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the workflows slice. Other slices (catalog, runs, releases, assistant) read
 * published definitions through this port, never through the store.
 */
public interface WorkflowQueries {

    /** The current published version of a workflow; empty Mono when none. */
    Mono<WorkflowRecord> published(String name);

    /** A specific version, published or not; empty Mono when unknown. */
    Mono<WorkflowRecord> version(String name, String version);

    /** The highest published version satisfying a range like {@code ^1}; empty when none. */
    Mono<WorkflowRecord> resolve(String name, String range);

    Flux<WorkflowRecord> publishedAll();

    /** The catalog the checks look things up in (providers, published workflows, resource types). */
    Mono<CheckContext> checkContext();

    /** Run the checks on YAML text against the current catalog. */
    Mono<List<Problem>> check(String yaml);

    // ------------------------------------------------------------------ the API read models

    /** One row of {@code GET /workflows}. {@code status} is draft or published. */
    record WorkflowSummary(String name, String title, String noun, String verb, String persona, String folder, String owner,
            List<String> tags, String status, String latestVersion, String publishedVersion, int stepCount, Instant updatedAt, boolean mcp) {
    }

    /** One version of a workflow; {@code rolledBack} is true on a retired version a release rollback took out (nothing resolves to it). */
    record WorkflowVersion(String version, WorkflowStatus status, String contentHash, Instant publishedAt, String publishedBy,
            String releaseId, String notes, Boolean rolledBack) {
    }

    /** {@code GET /workflows/{name}}: the summary plus the current file (the draft when there is one). */
    record WorkflowDetail(String name, String title, String noun, String verb, String persona, String folder, String owner,
            List<String> tags, String status, String latestVersion, String publishedVersion, int stepCount, Instant updatedAt, boolean mcp,
            String yaml, JsonNode definition, List<WorkflowVersion> versions, List<Problem> problems, List<ChangeRequest> changeRequests) {
    }

    /** {@code GET /workflows/{name}/versions/{version}}; {@code definition} is the YAML document as JSON. */
    record VersionDetail(String version, WorkflowStatus status, String yaml, JsonNode definition, String contentHash) {
    }

    /**
     * {@code GET /change-requests/{id}}: the change request, with its diff and yaml, and for a
     * workflow its {@code preview}.
     */
    record ChangeRequestDetail(@JsonUnwrapped ChangeRequest changeRequest, Preview preview) {
    }

    /** {@code mermaid}: the workflow the change request writes as a Mermaid flowchart ({@code WorkflowMermaid}). */
    record Preview(String mermaid) {
    }

    record Template(String id, String title, String description, String yaml) {
    }

    record StepTypeInfo(String type, String title, String category, JsonNode schema, String description) {
    }

    record RegistryAction(String id, String title, String effect, String retry, String approval, JsonNode input, JsonNode output, JsonNode sample) {
    }

    record RegistryProvider(String name, String title, String type, String owner, List<RegistryAction> actions) {
    }

    record RegistryWorkflow(String name, String title, String version, JsonNode input, JsonNode output) {
    }

    record Playbook(String name, String version, String path, String owner) {
    }

    /**
     * {@code GET /registry/dependencies}: how the published workflows and the resource types
     * depend on each other. {@code cycles} are runs that come back to where they started.
     */
    record Dependencies(List<TypeDependencies> resourceTypes, List<WorkflowDependencies> workflows, List<List<String>> cycles) {
    }

    /** Who produces a type (producedBy, register steps) and who consumes it (x-resource inputs, use steps). */
    record TypeDependencies(String type, List<DependencyLink> producers, List<DependencyLink> consumers) {
    }

    /** What a workflow produces and consumes, which workflows it runs and which workflows run it. */
    record WorkflowDependencies(String workflow, String version, List<String> produces, List<String> consumes, List<DependencyLink> runs,
            List<DependencyLink> consumers) {
    }

    /**
     * One link: the workflow at the other end, how ({@code producedBy}, {@code register},
     * {@code input}, {@code use}, {@code run}, {@code orCreateWith}), where ({@code at}: the step id
     * or input field) and, for a run step, its version range.
     */
    record DependencyLink(String workflow, String via, String at, String range) {
    }

    record CredentialName(String name, String type) {
    }

    /** {@code GET /workflows?folder=&status=&q=}; every filter may be null. */
    Flux<WorkflowSummary> list(String folder, String status, String q);

    /** 404 (NotFoundException) when unknown. */
    Mono<WorkflowDetail> detail(String name);

    Flux<WorkflowVersion> versions(String name);

    /** 404 when unknown. */
    Mono<VersionDetail> versionDetail(String name, String version);

    /** {@code status} is open, merged, closed or null. Rows carry no yaml or diff. */
    Flux<ChangeRequest> changeRequests(String status);

    /** With diff and yaml, and the preview of a workflow; 404 when unknown. */
    Mono<ChangeRequestDetail> changeRequest(String id);

    Flux<Template> templates();

    /** {@code GET /schemas/workflow.json}: the JSON Schema of a Workflow file, for editors. */
    Mono<JsonNode> workflowSchema();

    Flux<StepTypeInfo> stepTypes();

    Flux<RegistryProvider> registryProviders();

    /** {@code published} null or true: published versions; false: the latest version of every workflow. */
    Flux<RegistryWorkflow> registryWorkflows(Boolean published);

    /** Up to five summaries by fuzzy match on name, title, tags, noun and verb. */
    Flux<WorkflowSummary> searchWorkflows(String q);

    /** The registry's resource types, as the resources slice serves them. */
    Flux<ResourceTypeDefinition> resourceTypes();

    Flux<Playbook> playbooks();

    /** Producers and consumers of every resource type and published workflow, and the cycles among runs. */
    Mono<Dependencies> dependencies();

    Flux<CredentialName> credentials();
}
