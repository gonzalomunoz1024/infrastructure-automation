package com.iaf.console.bff.jobs.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    public static JobView summary(JobSummary job) {
        return new JobView(job.id(), job.workflow(), job.title(), job.version(), job.status() == null ? "unknown" : job.status().id(), requesterOf(job.requester(),
                job.onBehalfOf()), job.context() == null ? null : job.context().wfrn(), job.startedAt(), job.endedAt(), job.durationMs(), kindOf(job.kind()),
                progress(job.progress()), job.errorClass(), job.failedStep(), null, null, null, null, null, null, null, null);
    }

    public static JobView detail(JobDetail job, List<Object> approvals, List<String> can) {
        List<Step> steps = job.steps() == null ? null : job.steps().stream().map(JobView::step).toList();
        List<JobView> children = job.children() == null ? null : job.children().stream().map(JobView::summary).toList();
        Attention attention = job.attention() == null ? null : new Attention(job.attention().step(), job.attention().options(), job.attention().since());
        return new JobView(job.id(), job.workflow(), job.title(), job.version(), job.status() == null ? "unknown" : job.status().id(),
                requesterOf(job.requester(), job.onBehalfOf()), job.context() == null ? null : job.context().wfrn(), job.startedAt(), job.endedAt(),
                job.durationMs(), kindOf(job.kind()), progress(job.progress()), job.errorClass(), job.failedStep(), job.input(), job.output(), steps,
                approvals, job.preview(), attention, children, can);
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
