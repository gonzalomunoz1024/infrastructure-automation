package com.iaf.platform.runs.adapters.outbound.temporal;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The production twin of {@code InMemoryWorkflowExecution}: a job becomes an
 * {@link InterpreterWorkflow} execution whose id is the job id, on
 * {@code platform.temporal.task-queue}; approvals, attention decisions and cancels become its
 * signals; "running" is what the server says about the execution. Every Temporal call blocks,
 * so it runs on {@code boundedElastic}. Active with the {@code temporal} profile
 * ({@code platform.execution.mode: temporal} is the matching setting), replacing the in-memory
 * engine, which stays the default.
 */
@Slf4j
@Component
@Profile("temporal")
@Primary
public class TemporalWorkflowExecution implements WorkflowExecutionPort {

    private final WorkflowClient client;
    private final PlatformProperties.Temporal temporal;
    private final String mode;

    public TemporalWorkflowExecution(WorkflowClient client, PlatformProperties props) {
        this.client = client;
        this.temporal = props.getTemporal();
        this.mode = props.getExecution().getMode();
    }

    @PostConstruct
    void announce() {
        log.info("jobs run on Temporal: target {}, namespace {}, task queue {}", temporal.getTarget(), temporal.getNamespace(), temporal.getTaskQueue());
        if (!"temporal".equals(mode)) {
            log.warn("platform.execution.mode is '{}' while the temporal profile is active: the Temporal engine runs the jobs; set it to 'temporal'", mode);
        }
    }

    @Override
    public Mono<Void> start(Job job) {
        return blocking(() -> {
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId(job.id())
                    .setTaskQueue(temporal.getTaskQueue())
                    .setWorkflowIdReusePolicy(reusePolicy(job))
                    .build();
            InterpreterWorkflow workflow = client.newWorkflowStub(InterpreterWorkflow.class, options);
            try {
                WorkflowClient.start(workflow::run, job);
            } catch (WorkflowExecutionAlreadyStarted e) {
                throw new ConflictException("Job " + job.id() + " is already running" + (job.isBatch() ? "" : " or has already run"));
            }
            log.debug("job {} started as a workflow on {}", job.id(), temporal.getTaskQueue());
        });
    }

    @Override
    public Mono<Void> signalApproval(String jobId, String approvalId, ApprovalDecision decision) {
        return signal(jobId, w -> w.approve(approvalId, decision));
    }

    @Override
    public Mono<Void> signalAttention(String jobId, AttentionDecision decision) {
        return signal(jobId, w -> w.attention(decision));
    }

    @Override
    public Mono<Void> cancel(String jobId, String by) {
        return signal(jobId, w -> w.cancel(by));
    }

    @Override
    public Mono<Boolean> isRunning(String jobId) {
        return Mono.fromCallable(() -> {
            try {
                return client.newUntypedWorkflowStub(jobId).describe().getStatus() == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
            } catch (WorkflowNotFoundException e) {
                return false;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                    return false;
                }
                throw e;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * {@code temporal}: the job's execution in the Temporal web UI ({@code platform.temporal.ui-url}),
     * whose workflow id is the job id; the UI opens its latest run. No link without a UI URL.
     */
    @Override
    public Map<String, String> links(String jobId) {
        return temporalLink(temporal, jobId).map(url -> Map.of(TEMPORAL_LINK, url)).orElse(Map.of());
    }

    static final String TEMPORAL_LINK = "temporal";

    static Optional<String> temporalLink(PlatformProperties.Temporal temporal, String jobId) {
        String ui = temporal.getUiUrl() == null ? "" : temporal.getUiUrl().trim();
        if (ui.isEmpty() || jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        while (ui.endsWith("/")) {
            ui = ui.substring(0, ui.length() - 1);
        }
        return Optional.of(ui + "/namespaces/" + UriUtils.encodePathSegment(temporal.getNamespace(), StandardCharsets.UTF_8)
                + "/workflows/" + UriUtils.encodePathSegment(jobId, StandardCharsets.UTF_8));
    }

    /**
     * A job id runs once: a duplicate start is refused (409). The one exception is a batch, whose
     * failed rows are retried by starting the same job again once it ended; a batch that is
     * still running is refused all the same.
     */
    static WorkflowIdReusePolicy reusePolicy(Job job) {
        return job.isBatch() ? WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE
                : WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
    }

    private Mono<Void> signal(String jobId, Consumer<InterpreterWorkflow> signal) {
        return blocking(() -> {
            try {
                signal.accept(client.newWorkflowStub(InterpreterWorkflow.class, jobId));
            } catch (WorkflowNotFoundException e) {
                throw new NotFoundException("job", jobId);
            }
        });
    }

    private static Mono<Void> blocking(Runnable call) {
        return Mono.<Void>fromRunnable(call).subscribeOn(Schedulers.boundedElastic());
    }
}
