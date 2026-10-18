package com.iaf.platform.observability.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.tracing.TraceIds;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.runs.ports.outbound.StepTraces;

import io.micrometer.observation.Observation;
import jakarta.annotation.PostConstruct;

/**
 * Spans for jobs and their steps, from the job events. A job is an {@value #JOB_SPAN} span from
 * {@code job.started} to {@code job.finished} (or {@code job.cancelled}) in the trace its
 * {@code traceId} names: under the {@code run} step that started it, under its batch, under the
 * request that is starting it, or else as the root of that trace. Every step attempt is an
 * {@value #STEP_SPAN} span from {@code running} to its end, under the step that contains it (a
 * fork's branches run on threads of their own and hang under the fork); {@code waiting} is an
 * event on it and a failure records its error class. Provider calls hang under the step running
 * on the calling thread ({@link #parentOf}). Only a {@link LiveEvents live} {@code job.started}
 * opens a job's span: the history the demo seeds into the past makes none. A step's call on a
 * team's worker carries the {@code traceparent} of the step's span ({@link #traceparent}).
 */
@Component
public class JobTracing implements StepTraces {

    public static final String JOB_SPAN = "iaf.job";
    public static final String STEP_SPAN = "iaf.step";
    static final int MAX_OPEN_JOBS = 10_000;

    private final EventBus events;
    private final Traces traces;
    private final Clock clock;
    private final Map<String, OpenJob> jobs = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OpenJob> eldest) {
            if (size() <= MAX_OPEN_JOBS) {
                return false;
            }
            end(eldest.getValue(), "evicted", null);
            return true;
        }
    };
    /** The steps running on this thread, innermost first. */
    private final ThreadLocal<Deque<OpenStep>> running = new ThreadLocal<>();

    public JobTracing(EventBus events, Traces traces, Clock clock) {
        this.events = events;
        this.traces = traces == null ? Traces.off() : traces;
        this.clock = clock;
    }

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    private static final class OpenJob {
        final String id;
        final String workflow;
        final Observation span;
        final List<OpenStep> steps = new ArrayList<>();

        OpenJob(String id, String workflow, Observation span) {
            this.id = id;
            this.workflow = workflow;
            this.span = span;
        }
    }

    private static final class OpenStep {
        final OpenJob job;
        final String id;
        final String type;
        final Observation span;
        volatile boolean ended;

        OpenStep(OpenJob job, String id, String type, Observation span) {
            this.job = job;
            this.id = id;
            this.type = type;
            this.span = span;
        }
    }

    void on(DomainEvent event) {
        if (event == null || event.type() == null || !event.type().startsWith("job.") || event.data() == null) {
            return;
        }
        JsonNode d = event.data();
        String jobId = text(d, "jobId", event.aggregateId());
        if (jobId == null) {
            return;
        }
        switch (event.type()) {
            case "job.started" -> started(jobId, d, event.at());
            case "job.step" -> step(jobId, d);
            case "job.finished" -> finished(jobId, text(d, "status", "finished"), text(d, "errorClass", null));
            case "job.cancelled" -> finished(jobId, "cancelled", null);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- jobs

    private void started(String jobId, JsonNode d, Instant at) {
        if (!LiveEvents.live(at, clock)) {
            return;
        }
        String workflow = text(d, "workflow", "unknown");
        String traceId = text(d, "traceId", null);
        Map<String, String> low = new LinkedHashMap<>();
        low.put("workflow", workflow);
        low.put("kind", text(d, "kind", "single"));
        low.put("channel", text(d, "channel", "rest"));
        Map<String, String> high = new LinkedHashMap<>();
        high.put("job.id", jobId);
        high.put("workflow.version", text(d, "version", null));
        high.put("job.parent", text(d, "parentId", null));
        high.put("job.row", d.hasNonNull("row") ? d.get("row").asText() : null);
        high.put("job.rerun-of", text(d, "rerunOf", null));
        Observation parent = parentOfJob(text(d, "parentId", null), traceId);
        String name = "job " + workflow;
        Observation span = parent != null ? traces.start(JOB_SPAN, name, parent, low, high)
                : traces.continueTrace(JOB_SPAN, name, traceId, null, low, high);
        if (span.isNoop()) {
            return;
        }
        OpenJob previous;
        synchronized (jobs) {
            previous = jobs.put(jobId, new OpenJob(jobId, workflow, span));
        }
        if (previous != null) {
            end(previous, "restarted", null);
        }
    }

    /** The run step starting a child on this thread, the parent job (a batch), else the request remembered under the trace id. */
    private Observation parentOfJob(String parentId, String traceId) {
        if (parentId != null) {
            OpenStep step = innermost(parentId);
            if (step != null) {
                return step.span;
            }
            OpenJob parent = job(parentId);
            if (parent != null) {
                return parent.span;
            }
        }
        return traces.recall(traceId);
    }

    private void finished(String jobId, String status, String errorClass) {
        OpenJob job;
        synchronized (jobs) {
            job = jobs.remove(jobId);
        }
        if (job != null) {
            end(job, status, errorClass);
        }
    }

    private void end(OpenJob job, String status, String errorClass) {
        List<OpenStep> open;
        synchronized (job) {
            open = new ArrayList<>(job.steps);
        }
        for (int i = open.size() - 1; i >= 0; i--) {
            close(open.get(i), "cancelled".equals(status) ? "cancelled" : "abandoned", null);
        }
        job.span.lowCardinalityKeyValue("status", status);
        if (errorClass != null && !"succeeded".equals(status)) {
            Traces.failed(job.span, errorClass);
        } else {
            job.span.lowCardinalityKeyValue(Traces.ERROR_CLASS, Traces.NONE);
        }
        job.span.stop();
    }

    private OpenJob job(String jobId) {
        synchronized (jobs) {
            return jobs.get(jobId);
        }
    }

    // ---------------------------------------------------------------- steps

    private void step(String jobId, JsonNode d) {
        OpenJob job = job(jobId);
        if (job == null || d.path("replayed").asBoolean(false)) {
            return;
        }
        String stepId = text(d, "step", null);
        String status = text(d, "status", "");
        if (stepId == null) {
            return;
        }
        switch (status) {
            case "running" -> open(job, stepId, d);
            case "waiting" -> {
                OpenStep s = find(job, stepId);
                (s == null ? open(job, stepId, d) : s).span.event(Observation.Event.of("waiting"));
            }
            case "succeeded", "failed", "skipped", "compensated" -> {
                OpenStep s = find(job, stepId);
                close(s == null ? open(job, stepId, d) : s, status, "failed".equals(status) ? text(d.path("error"), "class", "error") : null);
            }
            default -> {
            }
        }
    }

    private OpenStep open(OpenJob job, String stepId, JsonNode d) {
        String type = text(d, "type", "step");
        OpenStep container = innermost(job.id);
        Observation parent = container != null ? container.span : forkOf(job);
        Map<String, String> low = new LinkedHashMap<>();
        low.put("workflow", job.workflow);
        low.put("step.type", type);
        Map<String, String> high = new LinkedHashMap<>();
        high.put("job.id", job.id);
        high.put("step.id", stepId);
        high.put("step.name", text(d, "name", null));
        high.put("attempt", d.hasNonNull("attempt") ? d.get("attempt").asText() : null);
        OpenStep s = new OpenStep(job, stepId, type, traces.start(STEP_SPAN, "step " + stepId, parent == null ? job.span : parent, low, high));
        synchronized (job) {
            job.steps.add(s);
        }
        Deque<OpenStep> here = running.get();
        if (here == null) {
            here = new ArrayDeque<>();
            running.set(here);
        }
        here.removeIf(o -> o.ended);
        here.push(s);
        return s;
    }

    /** The innermost fork of the job still running: what a step on a branch thread hangs under. */
    private static Observation forkOf(OpenJob job) {
        synchronized (job) {
            for (int i = job.steps.size() - 1; i >= 0; i--) {
                OpenStep s = job.steps.get(i);
                if ("fork".equals(s.type) && !s.ended) {
                    return s.span;
                }
            }
        }
        return null;
    }

    private OpenStep find(OpenJob job, String stepId) {
        Deque<OpenStep> here = running.get();
        if (here != null) {
            for (OpenStep s : here) {
                if (!s.ended && s.job == job && s.id.equals(stepId)) {
                    return s;
                }
            }
        }
        synchronized (job) {
            for (int i = job.steps.size() - 1; i >= 0; i--) {
                OpenStep s = job.steps.get(i);
                if (!s.ended && s.id.equals(stepId)) {
                    return s;
                }
            }
        }
        return null;
    }

    private void close(OpenStep s, String status, String errorClass) {
        synchronized (s.job) {
            if (s.ended) {
                return;
            }
            s.ended = true;
            s.job.steps.remove(s);
        }
        Deque<OpenStep> here = running.get();
        if (here != null) {
            here.remove(s);
            if (here.isEmpty()) {
                running.remove();
            }
        }
        s.span.lowCardinalityKeyValue("status", status);
        if (errorClass != null) {
            Traces.failed(s.span, errorClass);
        } else {
            s.span.lowCardinalityKeyValue(Traces.ERROR_CLASS, Traces.NONE);
        }
        s.span.stop();
    }

    /** The innermost step of {@code jobId} running on this thread; null when none is. */
    private OpenStep innermost(String jobId) {
        Deque<OpenStep> here = running.get();
        if (here == null) {
            return null;
        }
        for (OpenStep s : here) {
            if (!s.ended && s.job.id.equals(jobId)) {
                return s;
            }
        }
        return null;
    }

    /**
     * What a provider call of {@code jobId} made on this thread hangs under: the step running here,
     * else the job's own span; null when the job has no open span (a preview, a "Try it", history).
     */
    public Observation parentOf(String jobId) {
        if (jobId == null) {
            return null;
        }
        OpenStep s = innermost(jobId);
        if (s != null) {
            return s.span;
        }
        OpenJob job = job(jobId);
        return job == null ? null : job.span;
    }

    /**
     * The {@code traceparent} of {@code stepId}'s open span in job {@code jobId}, from whichever
     * thread opened it (on Temporal an activity publishes the step's events and another asks), else
     * of the job's span; without either (tracing off, or the job's span lives on another instance)
     * one in the job's trace under a span id made up for it.
     */
    @Override
    public String traceparent(String jobId, String traceId, String stepId) {
        OpenJob job = jobId == null ? null : job(jobId);
        OpenStep step = job == null || stepId == null ? null : find(job, stepId);
        Observation span = step != null ? step.span : job == null ? null : job.span;
        String trace = span == null ? null : traces.traceId(span);
        String spanId = span == null ? null : traces.spanId(span);
        if (trace != null && spanId != null) {
            return TraceIds.traceparent(trace, spanId, true);
        }
        return StepTraces.inTrace(traceId);
    }

    /** How many jobs have an open span (for tests and the bound). */
    int openJobs() {
        synchronized (jobs) {
            return jobs.size();
        }
    }

    private static String text(JsonNode d, String field, String fallback) {
        JsonNode v = d == null ? null : d.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
