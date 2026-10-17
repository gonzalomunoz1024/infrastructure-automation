package com.iaf.platform.runs.adapters.inbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Jobs, Approvals and Triggers endpoints of docs/api.md. The generated catalog endpoints
 * ({@code POST /api/v1/vms/{vmId}:restart}) live in the catalog slice and call the same
 * {@link RunsCommands}; {@code POST /api/v1/jobs} and {@code /api/v1/jobs:batch} are the plain
 * forms for clients that know the workflow name.
 */
@RestController
@RequestMapping("/api/v1")
public class RestControllerRunsAdapter implements RunsCommands, RunsQueries {

    private final RunsCommandHandler commands;
    private final RunsQueryHandler queries;

    public RestControllerRunsAdapter(RunsCommandHandler commands, RunsQueryHandler queries) {
        this.commands = commands;
        this.queries = queries;
    }

    /** {@code POST /api/v1/jobs}: {@code { workflow, version?, input, channel?, traceId?, changeId? }}. */
    public record StartJobRequest(String workflow, String version, JsonNode input, String channel, String traceId, String changeId) {
    }

    /** {@code POST /api/v1/jobs:batch}: {@code { workflow, version?, items, defaults?, rollout? }}. */
    public record StartBatchRequest(String workflow, String version, List<JsonNode> items, JsonNode defaults, RolloutPolicy rollout, String channel) {
    }

    public record RetryRequest(String step, JsonNode input) {
    }

    public record SkipRequest(String step) {
    }

    public record RerunRequest(String fromStep, JsonNode input) {
    }

    public record DecisionRequest(String comment) {
    }

    public record EventRequest(String topic, JsonNode payload) {
    }

    // ---------------------------------------------------------------- jobs: reads

    @Override
    @GetMapping("/jobs")
    public Flux<JsonNode> jobs(@RequestParam(required = false) String workflow, @RequestParam(required = false) String status,
            @RequestParam(required = false) String parent, @RequestParam(required = false) Integer limit) {
        return queries.jobs(workflow, status, parent, limit);
    }

    @Override
    @GetMapping("/jobs/{id}")
    public Mono<JsonNode> job(@PathVariable String id) {
        return queries.job(id).switchIfEmpty(Mono.error(() -> new NotFoundException("job", id)));
    }

    @Override
    public Flux<JsonNode> events(String id) {
        return queries.events(id);
    }

    /** SSE: history first, then live until the job ends. {@code id} is the per-job seq, {@code event} the JobEvent type. */
    @GetMapping(value = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<JsonNode>> eventStream(@PathVariable String id) {
        return queries.events(id).map(e -> ServerSentEvent.<JsonNode>builder(e)
                .id(e.path("seq").asText())
                .event(e.path("type").asText("job"))
                .build());
    }

    @Override
    @GetMapping("/jobs/{id}/replay")
    public Mono<JsonNode> replay(@PathVariable String id, @RequestParam(required = false) String compareTo) {
        return queries.replay(id, compareTo).switchIfEmpty(Mono.error(() -> new NotFoundException("job", id)));
    }

    @Override
    public Mono<List<JobOutcome>> outcomes(String workflow, String version, Instant since) {
        return queries.outcomes(workflow, version, since);
    }

    @Override
    public Mono<List<JsonNode>> steps(String id) {
        return queries.steps(id);
    }

    // ---------------------------------------------------------------- jobs: commands

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> start(@RequestBody StartJobRequest body) {
        return commands.startJob(new StartJob(body.workflow(), body.version(), body.input(), null,
                body.channel() == null ? "rest" : body.channel(), null, body.traceId(), null, body.changeId()));
    }

    @PostMapping("/jobs:batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> batch(@RequestBody StartBatchRequest body) {
        return commands.startBatch(new StartBatch(body.workflow(), body.version(), body.items() == null ? List.of() : body.items(),
                body.defaults(), body.rollout(), null, body.channel() == null ? "batch" : body.channel(), null));
    }

    @Override
    public Mono<CommandAccepted> startJob(StartJob command) {
        return commands.startJob(command);
    }

    @Override
    public Mono<CommandAccepted> startBatch(StartBatch command) {
        return commands.startBatch(command);
    }

    @Override
    @PostMapping("/jobs/{id}:cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> cancel(@PathVariable String id) {
        return commands.cancel(id);
    }

    @PostMapping("/jobs/{id}:retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> retryHttp(@PathVariable String id, @RequestBody(required = false) RetryRequest body) {
        return commands.retry(id, body == null ? null : body.step(), body == null ? null : body.input());
    }

    @Override
    public Mono<CommandAccepted> retry(String jobId, String step, JsonNode input) {
        return commands.retry(jobId, step, input);
    }

    @PostMapping("/jobs/{id}:skip")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> skipHttp(@PathVariable String id, @RequestBody(required = false) SkipRequest body) {
        return commands.skip(id, body == null ? null : body.step());
    }

    @Override
    public Mono<CommandAccepted> skip(String jobId, String step) {
        return commands.skip(jobId, step);
    }

    @Override
    @PostMapping("/jobs/{id}:abort")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> abort(@PathVariable String id) {
        return commands.abort(id);
    }

    @PostMapping("/jobs/{id}:rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> rerunHttp(@PathVariable String id, @RequestBody(required = false) RerunRequest body) {
        return commands.rerun(id, body == null ? null : body.fromStep(), body == null ? null : body.input());
    }

    @Override
    public Mono<CommandAccepted> rerun(String jobId, String fromStep, JsonNode input) {
        return commands.rerun(jobId, fromStep, input);
    }

    @Override
    @PostMapping("/jobs/{id}:retry-failed-rows")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> retryFailedRows(@PathVariable String id) {
        return commands.retryFailedRows(id);
    }

    // ---------------------------------------------------------------- approvals

    @Override
    @GetMapping("/approvals")
    public Flux<JsonNode> approvals(@RequestParam(required = false) String status) {
        return queries.approvals(status);
    }

    @Override
    @GetMapping("/approvals/{id}")
    public Mono<JsonNode> approval(@PathVariable String id) {
        return queries.approval(id).switchIfEmpty(Mono.error(() -> new NotFoundException("approval", id)));
    }

    @PostMapping("/approvals/{id}:approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> approveHttp(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        return commands.approve(id, body == null ? null : body.comment());
    }

    @Override
    public Mono<CommandAccepted> approve(String approvalId, String comment) {
        return commands.approve(approvalId, comment);
    }

    @PostMapping("/approvals/{id}:reject")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<CommandAccepted> rejectHttp(@PathVariable String id, @RequestBody(required = false) DecisionRequest body) {
        return commands.reject(id, body == null ? null : body.comment());
    }

    @Override
    public Mono<CommandAccepted> reject(String approvalId, String comment) {
        return commands.reject(approvalId, comment);
    }

    // ---------------------------------------------------------------- dry runs and previews (no endpoint: the workflows and catalog slices call these)

    @Override
    public Mono<DryRunResult> dryRun(WorkflowDefinition definition, JsonNode input, Identity who) {
        return commands.dryRun(definition, input, who);
    }

    @Override
    public Mono<Preview> preview(WorkflowDefinition definition, JsonNode input, Identity who) {
        return commands.preview(definition, input, who);
    }

    // ---------------------------------------------------------------- triggers

    @Override
    @PostMapping("/triggers/alerts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TriggerResult> triggerAlert(@RequestBody JsonNode alert) {
        return commands.triggerAlert(alert);
    }

    @PostMapping("/triggers/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TriggerResult> triggerEventHttp(@RequestBody EventRequest body) {
        return commands.triggerEvent(body == null ? null : body.topic(), body == null ? null : body.payload());
    }

    @Override
    public Mono<TriggerResult> triggerEvent(String topic, JsonNode payload) {
        return commands.triggerEvent(topic, payload);
    }

    static List<JsonNode> list(List<JsonNode> items) {
        return items == null ? new ArrayList<>() : items;
    }
}
