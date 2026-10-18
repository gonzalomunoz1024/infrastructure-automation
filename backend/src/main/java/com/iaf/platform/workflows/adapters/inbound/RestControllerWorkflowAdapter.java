package com.iaf.platform.workflows.adapters.inbound;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.workflows.application.WorkflowCommandHandler;
import com.iaf.platform.workflows.application.WorkflowQueryHandler;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.CheckContext;
import com.iaf.platform.workflows.domain.ReplayResult;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowCommands;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Workflows, Registry, change-requests, templates and schemas sections of docs/api.md. POST maps to
 * a command, GET to a query; the in-process methods of {@link WorkflowQueries} that other slices
 * call have no HTTP mapping.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RestControllerWorkflowAdapter implements WorkflowCommands, WorkflowQueries {

    private final WorkflowCommandHandler commands;
    private final WorkflowQueryHandler queries;

    // ------------------------------------------------------------------ workflows

    @Override
    @GetMapping("/workflows")
    public Flux<WorkflowSummary> list(@RequestParam(required = false) String folder, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return queries.list(folder, status, q);
    }

    @Override
    @GetMapping("/workflows/{name}")
    public Mono<WorkflowDetail> detail(@PathVariable String name) {
        return queries.detail(name);
    }

    @Override
    @GetMapping("/workflows/{name}/versions")
    public Flux<WorkflowVersion> versions(@PathVariable String name) {
        return queries.versions(name);
    }

    @Override
    @GetMapping("/workflows/{name}/versions/{version}")
    public Mono<VersionDetail> versionDetail(@PathVariable String name, @PathVariable String version) {
        return queries.versionDetail(name, version);
    }

    @Override
    @PutMapping("/workflows/{name}/draft")
    public Mono<DraftSaved> saveDraft(@PathVariable String name, @RequestBody WorkflowSource source) {
        return commands.saveDraft(name, source);
    }

    @Override
    @PostMapping("/workflows")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> createDraft(@RequestBody WorkflowSource source) {
        return commands.createDraft(source);
    }

    @Override
    @DeleteMapping("/workflows/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDraft(@PathVariable String name) {
        return commands.deleteDraft(name);
    }

    @Override
    @PostMapping("/workflows:validate")
    public Mono<Validation> validate(@RequestBody WorkflowSource source) {
        return commands.validate(source);
    }

    @Override
    @PostMapping("/workflows:dry-run")
    public Mono<DryRunResult> dryRun(@RequestBody DryRunRequest request) {
        return commands.dryRun(request);
    }

    @Override
    @PostMapping("/workflows/{name}:publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> publish(@PathVariable String name, @RequestBody(required = false) PublishWorkflow command) {
        return commands.publish(name, command);
    }

    @Override
    @PostMapping("/workflows/{name}/versions/{version}:replay-check")
    public Mono<ReplayResult> replayCheck(@PathVariable String name, @PathVariable String version) {
        return commands.replayCheck(name, version);
    }

    @Override
    @PostMapping("/workflows:import")
    public Mono<Imported> importFrom(@RequestBody ImportRequest request) {
        return commands.importFrom(request);
    }

    @Override
    @PostMapping("/workflows:render")
    public Mono<Rendered> render(@RequestBody WorkflowSource source) {
        return commands.render(source);
    }

    @Override
    @PostMapping("/workflows:parse")
    public Mono<Parsed> parse(@RequestBody WorkflowSource source) {
        return commands.parse(source);
    }

    // ------------------------------------------------------------------ change requests and templates

    @Override
    @GetMapping("/change-requests")
    public Flux<ChangeRequest> changeRequests(@RequestParam(required = false) String status) {
        return queries.changeRequests(status);
    }

    @Override
    @GetMapping("/change-requests/{id}")
    public Mono<ChangeRequestDetail> changeRequest(@PathVariable String id) {
        return queries.changeRequest(id);
    }

    @Override
    @PostMapping("/change-requests/{id}:merge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> mergeChangeRequest(@PathVariable String id) {
        return commands.mergeChangeRequest(id);
    }

    @Override
    @PostMapping("/change-requests/{id}:close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> closeChangeRequest(@PathVariable String id) {
        return commands.closeChangeRequest(id);
    }

    @Override
    @GetMapping("/templates")
    public Flux<Template> templates() {
        return queries.templates();
    }

    @Override
    @GetMapping(value = "/schemas/workflow.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> workflowSchema() {
        return queries.workflowSchema();
    }

    // ------------------------------------------------------------------ registry

    @Override
    @GetMapping("/registry/step-types")
    public Flux<StepTypeInfo> stepTypes() {
        return queries.stepTypes();
    }

    @Override
    @GetMapping("/registry/providers")
    public Flux<RegistryProvider> registryProviders() {
        return queries.registryProviders();
    }

    @Override
    @GetMapping("/registry/workflows")
    public Flux<RegistryWorkflow> registryWorkflows(@RequestParam(required = false) Boolean published) {
        return queries.registryWorkflows(published);
    }

    @Override
    @GetMapping("/registry/workflows/search")
    public Flux<WorkflowSummary> searchWorkflows(@RequestParam(required = false) String q) {
        return queries.searchWorkflows(q);
    }

    @Override
    @GetMapping("/registry/resource-types")
    public Flux<ResourceTypeDefinition> resourceTypes() {
        return queries.resourceTypes();
    }

    @Override
    @GetMapping("/registry/playbooks")
    public Flux<Playbook> playbooks() {
        return queries.playbooks();
    }

    @Override
    @GetMapping("/registry/credentials")
    public Flux<CredentialName> credentials() {
        return queries.credentials();
    }

    @Override
    @GetMapping("/registry/dependencies")
    public Mono<Dependencies> dependencies() {
        return queries.dependencies();
    }

    // ------------------------------------------------------------------ the in-process port (no HTTP mapping)

    @Override
    public Mono<WorkflowRecord> published(String name) {
        return queries.published(name);
    }

    @Override
    public Mono<WorkflowRecord> version(String name, String version) {
        return queries.version(name, version);
    }

    @Override
    public Mono<WorkflowRecord> resolve(String name, String range) {
        return queries.resolve(name, range);
    }

    @Override
    public Flux<WorkflowRecord> publishedAll() {
        return queries.publishedAll();
    }

    @Override
    public Mono<CheckContext> checkContext() {
        return queries.checkContext();
    }

    @Override
    public Mono<List<Problem>> check(String yaml) {
        return queries.check(yaml);
    }
}
