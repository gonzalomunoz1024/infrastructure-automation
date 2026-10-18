package com.iaf.platform.runs.adapters.outbound;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * The {@code notify} step: the message goes through the notification port to the step's channel,
 * and to the inbox of the job's requester and the workflow's owner team (plus the teams the channel
 * is configured for). Ordered before the demo runner, so its simulated answer is only the fallback
 * where this runner is not wired. A dry run sends nothing. The step fails only when no adapter
 * delivered the message and one failed.
 *
 * <p>Output: {@code { delivered, channel, message, notificationId, via: [inbox, slack, email] }}.
 */
@Component
@Order(50)
public class NotifyAutomationRunner implements AutomationRunnerPort {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final NotificationCommands notifications;
    private final JobProjection projection;

    public NotifyAutomationRunner(NotificationCommands notifications, JobProjection projection) {
        this.notifications = notifications;
        this.projection = projection;
    }

    @Override
    public boolean supports(StepType type) {
        return type == StepType.NOTIFY;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        JsonNode with = request.with() == null ? F.objectNode() : request.with();
        String channel = text(with, "channel");
        String message = text(with, "message");
        ObjectNode out = F.objectNode();
        out.put("channel", channel);
        out.put("message", message);
        if (request.dryRun()) {
            out.put("delivered", false);
            out.put("dryRun", true);
            return Mono.just(out);
        }
        Job job = request.jobId() == null ? null : projection.get(request.jobId()).orElse(null);
        String workflow = job == null ? null : job.workflow();
        String owner = job == null || job.definition() == null || job.definition().metadata() == null ? null : job.definition().metadata().owner();
        String stepName = request.step() == null ? null : request.step().name();
        Notification n = Notification.builder()
                .kind(Notification.NOTIFY)
                .severity("info")
                .title((workflow == null ? "Workflow" : workflow) + (stepName == null || stepName.isBlank() ? "" : ": " + stepName))
                .message(message)
                .link(request.jobId() == null ? null : "/jobs/" + request.jobId())
                .channel(channel)
                .users(job == null || job.requesterId() == null ? List.of() : List.of(job.requesterId()))
                .teams(owner == null ? List.of() : List.of(owner))
                .jobId(request.jobId())
                .workflow(workflow)
                .build();
        return notifications.send(n).map(d -> {
            if (d.via().isEmpty() && !d.failed().isEmpty()) {
                throw new IllegalStateException("the message to " + (channel == null ? "nobody" : channel) + " was not delivered: " + d.failed() + " failed");
            }
            out.put("delivered", !d.via().isEmpty());
            out.put("notificationId", d.notification().id());
            d.via().forEach(out.putArray("via")::add);
            if (!d.failed().isEmpty()) {
                d.failed().forEach(out.putArray("failed")::add);
            }
            return out;
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
