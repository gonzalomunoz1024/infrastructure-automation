package com.iaf.console.bff.notifications.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import com.iaf.console.platform.model.FeedEvent;
import com.iaf.console.platform.model.Person;

/**
 * The platform feed's job events as notifications (decision 32.1). A top-level job that ends tells
 * the person it ran for ({@code job.succeeded}, or {@code job.failed} when it failed or was rolled
 * back; a cancelled job tells nobody), and so does one that pauses on a failed step
 * ({@code job.needs-attention}). A job that waits for an approval tells the members of the
 * approver group, and the decision tells the person the job runs for. Rows of a batch notify
 * nobody: their batch does. A job no person is named on ({@code onBehalfOf}) tells nobody but an
 * approver group.
 */
public final class FeedDrafts {

    static final String FINISHED = "job.finished";
    static final String ATTENTION = "job.attention";
    static final String APPROVAL_REQUESTED = "job.approval-requested";
    static final String APPROVAL_DECIDED = "job.approval-decided";
    static final String STARTED = "job.started";

    private FeedDrafts() {
    }

    /** What {@code job.started} tells about a job, for its later events; empty for any other event. */
    public static Optional<JobFacts> started(FeedEvent event) {
        if (event == null || !STARTED.equals(event.type()) || event.data() == null) {
            return Optional.empty();
        }
        JsonNode data = event.data();
        return Optional.of(new JobFacts(Texts.text(data, "title"), Texts.text(data, "workflow"), Texts.text(data, "parentId")));
    }

    /** The job an event is about: {@code data.jobId}, else the aggregate. */
    public static String jobIdOf(FeedEvent event) {
        String jobId = event.data() == null ? null : Texts.text(event.data(), "jobId");
        return jobId != null ? jobId : event.aggregateId();
    }

    /**
     * The notification an event makes, given what the job's start said (null when it was not
     * seen); empty for every other event, for a row of a batch, and for an event outside an account.
     */
    public static Optional<Draft> of(FeedEvent event, JobFacts job) {
        if (event == null || event.type() == null || event.key() == null || event.context() == null || event.context().accountId() == null) {
            return Optional.empty();
        }
        if (job != null && !job.topLevel()) {
            return Optional.empty();
        }
        JsonNode data = event.data() == null ? MissingNode.getInstance() : event.data();
        String jobId = jobIdOf(event);
        if (jobId == null) {
            return Optional.empty();
        }
        Facts facts = new Facts(event, data, jobId, nameOf(job, data, jobId));
        return switch (event.type()) {
            case FINISHED -> finished(facts);
            case ATTENTION -> attention(facts);
            case APPROVAL_REQUESTED -> approvalRequested(facts);
            case APPROVAL_DECIDED -> approvalDecided(facts);
            default -> Optional.empty();
        };
    }

    private record Facts(FeedEvent event, JsonNode data, String jobId, String name) {

        String wfrn() {
            return event.context().wfrn();
        }

        String resource() {
            return Texts.resourceName(wfrn());
        }

        Instant at() {
            return event.at() == null ? Instant.now() : event.at();
        }

        Draft draft(String kind, String severity, String title, String body, List<Audience> audience) {
            return new Draft(event.key(), kind, severity, title, body, event.context().accountId(), event.context().region(), wfrn(), jobId,
                    Links.job(jobId), at(), audience);
        }

        /** The person the job runs for, when the event names one. */
        Optional<Audience> requester() {
            Person person = event.onBehalfOf();
            if (person == null || person.id() == null || person.id().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Audience.person(person.id(), person.email(), person.name()));
        }

        String on() {
            return resource() == null ? "" : " on " + resource();
        }
    }

    private static Optional<Draft> finished(Facts f) {
        String status = Texts.text(f.data(), "status");
        if (status == null) {
            return Optional.empty();
        }
        Optional<Audience> requester = f.requester();
        if (requester.isEmpty()) {
            return Optional.empty();
        }
        long duration = f.data().path("durationMs").asLong(0);
        return switch (status) {
            case "succeeded" -> Optional.of(f.draft(NotificationKinds.JOB_SUCCEEDED, Notification.INFO, f.name() + " succeeded",
                    "Job " + f.jobId() + f.on() + " finished in " + Texts.duration(duration) + ".", List.of(requester.get())));
            case "failed", "rolled-back" -> {
                String title = f.name() + ("rolled-back".equals(status) ? " failed and was rolled back" : " failed");
                yield Optional.of(f.draft(NotificationKinds.JOB_FAILED, Notification.CRITICAL, title, failure(f), List.of(requester.get())));
            }
            default -> Optional.empty();
        };
    }

    private static String failure(Facts f) {
        String errorClass = Texts.text(f.data(), "errorClass");
        String message = Texts.text(f.data(), "message");
        String step = Texts.text(f.data(), "failedStep");
        StringBuilder body = new StringBuilder();
        if (message != null) {
            body.append(Texts.sentence(message + (errorClass == null ? "" : " (" + errorClass + ")"))).append(' ');
        } else if (errorClass != null) {
            body.append("The job failed with ").append(errorClass).append(". ");
        }
        if (step != null) {
            body.append("Step ").append(step).append(" failed. ");
        }
        body.append("Job ").append(f.jobId()).append(f.on()).append('.');
        return body.toString();
    }

    private static Optional<Draft> attention(Facts f) {
        Optional<Audience> requester = f.requester();
        if (requester.isEmpty()) {
            return Optional.empty();
        }
        String step = Texts.text(f.data(), "step");
        JsonNode error = f.data().path("error");
        String message = Texts.text(error, "message");
        String errorClass = Texts.text(error, "class");
        String body = (step == null ? "A step" : "Step " + step) + " failed"
                + (message == null ? "" : ": " + message + (errorClass == null ? "" : " (" + errorClass + ")"))
                + ". Retry it, skip it or abort the job from its page.";
        return Optional.of(f.draft(NotificationKinds.JOB_NEEDS_ATTENTION, Notification.WARNING, f.name() + " needs your attention", body,
                List.of(requester.get())));
    }

    private static Optional<Draft> approvalRequested(Facts f) {
        String group = Texts.text(f.data(), "group");
        if (group == null) {
            return Optional.empty();
        }
        String message = Texts.text(f.data(), "message");
        String step = Texts.text(f.data(), "step");
        Person person = f.event().onBehalfOf();
        String forWhom = person == null ? "" : " Asked for " + (person.name() != null ? person.name() : person.id()) + ".";
        String body = (message != null ? Texts.sentence(message)
                : "Job " + f.jobId() + " waits" + (step == null ? "" : " at step " + step) + " for the " + group + " group.") + forWhom;
        return Optional.of(f.draft(NotificationKinds.APPROVAL_REQUESTED, Notification.WARNING, "Approval needed: " + f.name(), body,
                List.of(Audience.group(group))));
    }

    private static Optional<Draft> approvalDecided(Facts f) {
        Optional<Audience> requester = f.requester();
        String decision = Texts.text(f.data(), "decision");
        if (requester.isEmpty() || decision == null) {
            return Optional.empty();
        }
        String by = Texts.text(f.data(), "by");
        String comment = Texts.text(f.data(), "comment");
        String title = switch (decision) {
            case "approved" -> f.name() + " was approved";
            case "rejected" -> f.name() + " was rejected";
            case "expired" -> f.name() + ": the approval expired";
            default -> f.name() + ": the approval was decided";
        };
        String body = ("expired".equals(decision) ? "No one decided in time" : "Decided by " + (by == null ? "someone" : by))
                + (comment == null ? "." : ": " + comment) + " Job " + f.jobId() + f.on() + ".";
        String severity = "approved".equals(decision) ? Notification.INFO : Notification.WARNING;
        return Optional.of(f.draft(NotificationKinds.APPROVAL_DECIDED, severity, title, body, List.of(requester.get())));
    }

    /** What to call a job: its title, else its workflow, else its id. */
    static String nameOf(JobFacts job, JsonNode data, String jobId) {
        if (job != null && job.title() != null) {
            return job.title();
        }
        String workflow = Texts.text(data, "workflow");
        if (workflow == null && job != null) {
            workflow = job.workflow();
        }
        return workflow != null ? workflow : "Job " + jobId;
    }
}
