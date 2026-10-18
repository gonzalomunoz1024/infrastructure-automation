package com.iaf.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * The platform's own notifications, from its events: a provider alert goes to the provider's alert
 * channel and its owner team; an approval request to the approver group; an escalation to the
 * escalation group, which may now decide. Events older than {@code fresh-for} (history being
 * seeded or replayed) notify nobody. Sending is asynchronous and never fails the publisher.
 */
@Slf4j
@Component
public class NotificationListener {

    static final String PROVIDER_ALERT = "provider.alert";
    static final String APPROVAL_REQUESTED = "job.approval-requested";
    static final String APPROVAL_ESCALATED = "job.approval-escalated";

    private final EventBus events;
    private final NotificationCommands notifications;
    private final NotificationProperties props;
    private final Clock clock;

    public NotificationListener(EventBus events, NotificationCommands notifications, NotificationProperties props, Clock clock) {
        this.events = events;
        this.notifications = notifications;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    public void subscribe() {
        events.register(this::on);
    }

    void on(DomainEvent event) {
        Optional<Notification> n;
        try {
            n = notificationFor(event);
        } catch (RuntimeException e) {
            log.warn("no notification for {} {}: {}", event.type(), event.aggregateId(), e.toString());
            return;
        }
        n.ifPresent(notification -> notifications.send(notification).subscribe(
                d -> {
                    if (!d.failed().isEmpty()) {
                        log.warn("notification {} ({}) failed on {}", d.notification().id(), d.notification().kind(), d.failed());
                    }
                },
                e -> log.warn("notification for {} {} not sent: {}", event.type(), event.aggregateId(), e.toString())));
    }

    /** The notification an event calls for, if any. */
    Optional<Notification> notificationFor(DomainEvent event) {
        if (event == null || event.type() == null || stale(event.at())) {
            return Optional.empty();
        }
        JsonNode d = event.data();
        return switch (event.type()) {
            case PROVIDER_ALERT -> Optional.of(alert(event, d));
            case APPROVAL_REQUESTED -> Optional.ofNullable(text(d, "group")).map(group -> approvalRequested(event, d, group));
            case APPROVAL_ESCALATED -> Optional.ofNullable(text(d, "to")).map(to -> approvalEscalated(event, d, to));
            default -> Optional.empty();
        };
    }

    private Notification alert(DomainEvent e, JsonNode d) {
        String provider = text(d, "provider") == null ? e.aggregateId() : text(d, "provider");
        String severity = text(d, "severity") == null ? "warning" : text(d, "severity");
        String action = text(d, "action");
        return Notification.builder()
                .at(e.at())
                .kind(Notification.PROVIDER_ALERT)
                .severity(severity)
                .title("Provider " + provider + (action == null ? "" : " · " + action) + ": " + severity)
                .message(text(d, "message"))
                .link("/providers/" + provider)
                .channel(text(d, "channel"))
                .teams(listOf(text(d, "owner")))
                .jobId(text(d, "jobId"))
                .provider(provider)
                .build();
    }

    private Notification approvalRequested(DomainEvent e, JsonNode d, String group) {
        String jobId = jobId(e, d);
        String workflow = text(d, "workflow");
        String message = text(d, "message");
        return Notification.builder()
                .at(e.at())
                .kind(Notification.APPROVAL_REQUESTED)
                .severity("info")
                .title("Approval needed" + (workflow == null ? "" : ": " + workflow))
                .message((message == null || message.isBlank() ? "" : message + " ") + "Job " + jobId + " waits for " + group
                        + (text(d, "step") == null ? "" : " at step " + text(d, "step")) + ".")
                .link("/jobs/" + jobId)
                .channel(teamChannel(group))
                .teams(List.of(group))
                .jobId(jobId)
                .workflow(workflow)
                .build();
    }

    private Notification approvalEscalated(DomainEvent e, JsonNode d, String to) {
        String jobId = jobId(e, d);
        return Notification.builder()
                .at(e.at())
                .kind(Notification.APPROVAL_ESCALATED)
                .severity("warning")
                .title("Approval escalated to " + to)
                .message("Job " + jobId + " waited too long for its approvers" + (text(d, "step") == null ? "" : " at step " + text(d, "step"))
                        + "; " + to + " can decide it now.")
                .link("/jobs/" + jobId)
                .channel(teamChannel(to))
                .teams(List.of(to))
                .jobId(jobId)
                .build();
    }

    /** The channel configured for a team, or none. */
    private String teamChannel(String team) {
        return props.team(team).map(NotificationProperties.Team::getChannel).filter(c -> !c.isBlank()).orElse(null);
    }

    private boolean stale(Instant at) {
        if (at == null || props.getFreshFor() == null) {
            return false;
        }
        return at.isBefore(clock.instant().minus(props.getFreshFor()));
    }

    private static String jobId(DomainEvent e, JsonNode d) {
        String id = text(d, "jobId");
        return id == null ? e.aggregateId() : id;
    }

    private static List<String> listOf(String value) {
        return value == null ? List.of() : List.of(value);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }
}
