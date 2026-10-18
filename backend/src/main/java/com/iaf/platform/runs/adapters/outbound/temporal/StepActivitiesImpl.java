package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.application.ChildStarts;
import com.iaf.platform.runs.application.JobEvents;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.application.PortStepExecutor;
import com.iaf.platform.runs.application.PortStepExecutor.Ports;
import com.iaf.platform.runs.application.StepExecutors;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.StepExecutor.ApprovalRequest;
import com.iaf.platform.runs.domain.StepExecutor.RegisterRequest;
import com.iaf.platform.runs.domain.StepExecutor.UseRequest;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import lombok.extern.slf4j.Slf4j;

/**
 * The activities over the slice's own beans: the calls of a step go through a
 * {@link PortStepExecutor} built for the call (the same executor the in-memory engine walks
 * with, over the same ports), records go through {@link JobFactory} and the {@link JobProjection},
 * and every event through {@link JobEvents}, so the projection, the audit listener and the
 * streams see exactly what the in-memory engine publishes. Runs inside the Spring process, on the
 * worker {@link TemporalConfig} starts.
 */
@Slf4j
@Component
@Profile("temporal")
public class StepActivitiesImpl implements StepActivities {

    static final Duration CHANGE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The engine the executor sees inside an activity: never cancelled, and everything that waits
     * or walks belongs to the workflow (a {@code use} that waits until ready is polled there with
     * timers, so the activity only ever does one lookup).
     */
    static final PortStepExecutor.Engine ENGINE = new PortStepExecutor.Engine() {
        @Override
        public Optional<ApprovalDecision> awaitDecision(JobContext ctx, String approvalId, Duration timeout) {
            throw new UnsupportedOperationException("approvals are decided inside the workflow");
        }

        @Override
        public Optional<AttentionDecision> awaitAttention(JobContext ctx, Attention attention, Duration timeout) {
            throw new UnsupportedOperationException("attention is decided inside the workflow");
        }

        @Override
        public void sleep(JobContext ctx, Duration duration) {
            throw new UnsupportedOperationException("waits are timers inside the workflow");
        }

        @Override
        public boolean cancelled(JobContext ctx) {
            return false;
        }

        @Override
        public JsonNode runChild(JobContext ctx, Step step, Job child, Duration timeout) {
            throw new UnsupportedOperationException("children run as child workflows");
        }

        @Override
        public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
            throw new UnsupportedOperationException("branches run inside the workflow");
        }
    };

    private final StepExecutors executors;
    private final JobProjection projection;
    private final JobEvents events;
    private final ChangeTicketPort changes;
    private final Clock clock;
    private final Duration heartbeatInterval;

    public StepActivitiesImpl(StepExecutors executors, JobProjection projection, JobEvents events, ChangeTicketPort changes, Clock clock,
            PlatformProperties props) {
        this.executors = executors;
        this.projection = projection;
        this.events = events;
        this.changes = changes;
        this.clock = clock;
        this.heartbeatInterval = props.getTemporal().getHeartbeatInterval();
    }

    // ---------------------------------------------------------------- the calls of a step

    /** The calls that can take long heartbeat while they run ({@link TemporalConfig} sets their heartbeat timeout). */
    @Override
    public JsonNode callProvider(StepCall call, String provider, String action, JsonNode input) {
        return Heartbeats.during(heartbeatInterval, () -> step(call, (ex, ctx) -> ex.callProvider(ctx, call.step(), provider, action, input, call.timeout())));
    }

    @Override
    public JsonNode runAutomation(StepCall call, JsonNode with) {
        return Heartbeats.during(heartbeatInterval, () -> step(call, (ex, ctx) -> ex.runAutomation(ctx, call.step(), with, call.timeout())));
    }

    @Override
    public JsonNode notify(StepCall call, String channel, String message) {
        return step(call, (ex, ctx) -> ex.notify(ctx, call.step(), channel, message));
    }

    @Override
    public JsonNode script(StepCall call, String language, String code, JsonNode input) {
        return Heartbeats.during(heartbeatInterval, () -> step(call, (ex, ctx) -> ex.script(ctx, call.step(), language, code, input, call.timeout())));
    }

    @Override
    public JsonNode useResource(StepCall call, UseRequest request) {
        return step(call, (ex, ctx) -> ex.useResource(ctx, call.step(), request, call.timeout()));
    }

    @Override
    public JsonNode registerResource(StepCall call, RegisterRequest request) {
        return step(call, (ex, ctx) -> ex.registerResource(ctx, call.step(), request));
    }

    @Override
    public WorkerRoute providerRoute(JobRef job, String stepId, String provider, String action) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        // a lookup that fails is retried by the local activity's own policy, then fails the step as an error;
        // the job's identity lets an active provider release route it to the candidate version's worker
        ProviderQueries.Use use = job == null ? null : new ProviderQueries.Use(job.id(), job.environment(), false);
        ProviderDefinition p = executors.ports().providers().provider(provider)
                .contextWrite(c -> ProviderQueries.Use.with(c, use))
                .blockOptional(PortStepExecutor.LOOKUP_TIMEOUT).orElse(null);
        if (p == null || p.type() != ProviderType.SDK) {
            return null;
        }
        if (action == null || p.action(action).isEmpty()) {
            throw new StepFailureData(stepId, "unknown_action", "Provider " + provider + " has no action '" + action + "'", false, false)
                    .toApplicationFailure();
        }
        String heartbeat = p.spec().worker() == null ? null : p.spec().worker().heartbeatTimeout();
        try {
            return new WorkerRoute(p.taskQueue(), Durations.parse(heartbeat, null));
        } catch (IllegalArgumentException e) {
            throw new StepFailureData(stepId, "misconfigured", "Provider " + provider + ": worker.heartbeatTimeout " + e.getMessage(), false, false)
                    .toApplicationFailure();
        }
    }

    private JsonNode step(StepCall call, BiFunction<PortStepExecutor, JobContext, JsonNode> body) {
        try {
            return body.apply(executors.live(call.job().toJob(), ENGINE), call.context());
        } catch (StepFailure f) {
            throw StepFailureData.of(f).toApplicationFailure();
        }
    }

    // ---------------------------------------------------------------- records and ids

    @Override
    public Job childJob(JobRef parent, String stepId, String workflow, String version, JsonNode input) {
        Ports ports = executors.ports();
        Job parentJob = parent.toJob();
        try {
            String range = ChildStarts.range(ports, stepId, workflow, version, message -> events.log(parent.id(), message));
            WorkflowDefinition child = ChildStarts.resolve(ports, stepId, workflow, range);
            return ChildStarts.prepare(ports, parentJob, stepId, child, input, clock.instant());
        } catch (StepFailure f) {
            throw StepFailureData.of(f).toApplicationFailure();
        }
    }

    /** The row's definition is the workflow's own (attached there), so this reads nothing from the projection of the instance it runs on. */
    @Override
    public Job rowJob(JobRef batch, BatchRow row) {
        return JobFactory.row(batch.toJob(), row, clock.instant()).withDefinition(null);
    }

    /** Idempotent: a retry after the store refused the event, or a child its parent recorded before, publishes the start once. */
    @Override
    public void started(Job job) {
        if (projection.history(job.id()).stream().anyMatch(e -> "job.started".equals(e.data().path("event").asText()))) {
            log.info("job {} is already recorded; its start is not published again", job.id());
            return;
        }
        if (!projection.has(job.id())) {
            projection.put(job);
        }
        events.started(job);
    }

    @Override
    public String requestApproval(JobRef job, Step step, ApprovalRequest request, Preview preview) {
        Job j = job.toJob().withPreview(preview);
        return executors.live(j, ENGINE).requestApproval(JobContext.of(j), step, request);
    }

    @Override
    public Approval batchApproval(JobRef batch, String group, String message, String timeout, Preview preview) {
        Approval a = Approval.builder()
                .id(Ids.next("apr"))
                .jobId(batch.id())
                .workflow(batch.workflow())
                .step("batch")
                .group(group)
                .message(message)
                .requestedAt(clock.instant())
                .timeout(timeout)
                .status(Approval.Status.PENDING)
                .preview(preview)
                .build();
        events.approvalRequested(a);
        return a;
    }

    @Override
    public void approvalDecided(Approval approval, ApprovalDecision decision) {
        events.approvalDecided(approval, decision);
    }

    @Override
    public void escalateApproval(String jobId, String stepId, String approvalId, String to) {
        events.approvalEscalated(jobId, approvalId, stepId, to);
    }

    // ---------------------------------------------------------------- events

    @Override
    public void publishAttention(String jobId, Attention attention) {
        events.attention(jobId, attention);
    }

    @Override
    public void attentionResolved(String jobId, String stepId, String action, String by, JsonNode input) {
        events.attentionResolved(jobId, stepId, action, by, input, null);
    }

    @Override
    public void publishStep(String jobId, StepEventData event) {
        events.step(jobId, event.toEvent());
    }

    @Override
    public void log(String jobId, String message) {
        events.log(jobId, message);
    }

    @Override
    public void wave(String jobId, Wave wave, String reason, List<Integer> rows, Instant at) {
        events.wave(jobId, wave, reason, rows, at);
    }

    @Override
    public void finished(JobRef job, Outcome outcome, Instant at) {
        long duration = job.startedAt() == null ? 0 : Math.max(0, at.toEpochMilli() - job.startedAt().toEpochMilli());
        events.finished(job.id(), job.workflow(), job.version(), outcome.status(), duration, outcome.errorClass(), outcome.failedStep(),
                outcome.output(), outcome.message(), at);
        closeChange(job, outcome.succeeded());
    }

    @Override
    public void cancelled(JobRef job, String by, Instant at) {
        events.cancelled(job.id(), by, at);
        closeChange(job, false);
    }

    private void closeChange(JobRef job, boolean success) {
        ChangeTicket change = job.change();
        if (change == null || change.id() == null || "linked".equals(change.state())) {
            return;
        }
        try {
            ChangeTicket closed = changes.close(change.id(), success).block(CHANGE_TIMEOUT);
            if (closed != null) {
                events.changeClosed(job.id(), closed);
            }
        } catch (RuntimeException e) {
            log.warn("could not close change {}: {}", change.id(), e.toString());
        }
    }
}
