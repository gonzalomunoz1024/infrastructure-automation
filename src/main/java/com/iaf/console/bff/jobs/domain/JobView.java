package com.iaf.console.bff.jobs.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.platform.model.Batch;
import com.iaf.console.platform.model.JobDetail;
import com.iaf.console.platform.model.JobStep;
import com.iaf.console.platform.model.JobSummary;

/** A platform job as the contract's {@code JobSummary} and {@code JobDetail}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobView(
        String jobId,
        String workflow,
        String title,
        String version,
        String status,
        String requester,
        String wfrn,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String kind,
        Progress progress,
        String errorClass,
        String failedStep,
        Map<String, Object> input,
        Map<String, Object> output,
        List<Step> steps,
        List<Object> approvals,
        Object preview,
        Attention attention,
        List<JobView> children,
        BatchView batch,
        List<String> can) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Progress(Integer done, Integer total, Integer failed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(String id, String name, String type, String status, Integer attempt, Instant startedAt, Instant endedAt, Long durationMs, StepError error) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepError(@com.fasterxml.jackson.annotation.JsonProperty("class") String errorClass, String message, Boolean retryable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attention(String step, List<String> options, Instant since) {
    }

    /**
     * A batch's rollout as it goes: the policy it runs under, each wave released so far (its share
     * of the rows, size and status), and every row with the resource it runs on and its child job;
     * {@code stoppedReason} once the failures stopped it. The rows' inputs stay out: the console
     * shows a request's input from its own copy, never from a platform read.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchView(Rollout rollout, List<WaveView> waves, List<RowView> rows, String stoppedReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rollout(List<Integer> waves, Integer maxParallel, Integer stopIfFailedPercent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WaveView(int index, int percent, int size, String status, Instant startedAt, Instant endedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowView(int index, String wfrn, String jobId, String status, String error, Integer wave) {
    }

    public static JobView summary(JobSummary job) {
        return new JobView(job.id(), job.workflow(), job.title(), job.version(), job.status() == null ? "unknown" : job.status().id(), requesterOf(job.requester(),
                job.onBehalfOf()), job.context() == null ? null : job.context().wfrn(), job.startedAt(), job.endedAt(), job.durationMs(), kindOf(job.kind()),
                progress(job.progress()), job.errorClass(), job.failedStep(), null, null, null, null, null, null, null, null, null);
    }

    public static JobView detail(JobDetail job, List<Object> approvals, List<String> can) {
        List<Step> steps = job.steps() == null ? null : job.steps().stream().map(JobView::step).toList();
        List<JobView> children = job.children() == null ? null : job.children().stream().map(JobView::summary).toList();
        Attention attention = job.attention() == null ? null : new Attention(job.attention().step(), job.attention().options(), job.attention().since());
        return new JobView(job.id(), job.workflow(), job.title(), job.version(), job.status() == null ? "unknown" : job.status().id(),
                requesterOf(job.requester(), job.onBehalfOf()), job.context() == null ? null : job.context().wfrn(), job.startedAt(), job.endedAt(),
                job.durationMs(), kindOf(job.kind()), progress(job.progress()), job.errorClass(), job.failedStep(), job.input(), job.output(), steps,
                approvals, job.preview(), attention, children, batch(job), can);
    }

    private static BatchView batch(JobDetail job) {
        Batch b = job.batch();
        if (b == null) {
            return null;
        }
        Map<String, String> wfrnByJob = new HashMap<>();
        Map<Integer, String> wfrnByRow = new HashMap<>();
        if (job.children() != null) {
            for (JobSummary child : job.children()) {
                String wfrn = child.context() == null ? null : child.context().wfrn();
                if (wfrn != null) {
                    wfrnByJob.put(child.id(), wfrn);
                    if (child.row() != null) {
                        wfrnByRow.put(child.row(), wfrn);
                    }
                }
            }
        }
        Rollout rollout = b.rollout() == null ? null : new Rollout(b.rollout().waves(), b.rollout().maxParallel(), b.rollout().stopIfFailedPercent());
        List<WaveView> waves = b.waves() == null ? List.of() : b.waves().stream()
                .map(w -> new WaveView(w.index(), w.percent(), w.size(), w.status(), w.startedAt(), w.endedAt())).toList();
        List<RowView> rows = b.rows() == null ? List.of() : b.rows().stream()
                .map(r -> new RowView(r.index(), r.jobId() != null && wfrnByJob.containsKey(r.jobId()) ? wfrnByJob.get(r.jobId()) : wfrnByRow.get(r.index()),
                        r.jobId(), r.status(), r.error(), r.wave()))
                .toList();
        return new BatchView(rollout, waves, rows, b.stoppedReason());
    }

    private static Step step(JobStep s) {
        StepError error = s.error() == null ? null : new StepError(s.error().errorClass(), s.error().message(), s.error().retryable());
        return new Step(s.id(), s.name(), s.type(), s.status(), s.attempt(), s.startedAt(), s.endedAt(), s.durationMs(), error);
    }

    private static Progress progress(JobSummary.Progress p) {
        return p == null ? null : new Progress(p.done(), p.total(), p.failed());
    }

    private static String kindOf(String kind) {
        return kind == null || kind.isBlank() ? "single" : ("batch".equals(kind) || "parent".equals(kind) ? "batch" : "single");
    }

    private static String requesterOf(String requester, com.iaf.console.platform.model.Person onBehalfOf) {
        if (onBehalfOf != null && onBehalfOf.id() != null) {
            return onBehalfOf.id();
        }
        return requester;
    }
}
