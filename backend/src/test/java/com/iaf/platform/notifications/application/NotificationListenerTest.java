package com.iaf.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.notifications.adapters.outbound.InAppNotifications;
import com.iaf.platform.notifications.adapters.outbound.InMemoryInboxStore;
import com.iaf.platform.notifications.domain.Notification;

class NotificationListenerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final JsonNodeFactory F = JsonNodeFactory.instance;

    final NotificationProperties props = new NotificationProperties();
    final InMemoryInboxStore store = new InMemoryInboxStore(100);
    final EventBus bus = new EventBus(CLOCK);
    final NotificationHandler handler = new NotificationHandler(List.of(new InAppNotifications(store)), store, props, CLOCK);
    final NotificationListener listener = new NotificationListener(bus, handler, props, CLOCK);

    static DomainEvent event(String type, String aggregateId, Instant at, ObjectNode data) {
        return new DomainEvent("evt_1", at, type, type.startsWith("provider") ? "provider" : "job", aggregateId, "platform", data);
    }

    @Test
    void aProviderAlertGoesToItsAlertChannelAndItsOwnerTeam() {
        ObjectNode d = F.objectNode().put("provider", "vsphere").put("action", "restartVm").put("message", "Call restartVm failed (timeout)")
                .put("severity", "warning").put("jobId", "job_7").put("channel", "#vsphere-alerts").put("owner", "team-compute").put("class", "timeout");

        Notification n = listener.notificationFor(event("provider.alert", "vsphere", NOW, d)).orElseThrow();

        assertThat(n.kind()).isEqualTo(Notification.PROVIDER_ALERT);
        assertThat(n.channel()).isEqualTo("#vsphere-alerts");
        assertThat(n.teams()).containsExactly("team-compute");
        assertThat(n.severity()).isEqualTo("warning");
        assertThat(n.title()).isEqualTo("Provider vsphere · restartVm: warning");
        assertThat(n.message()).isEqualTo("Call restartVm failed (timeout)");
        assertThat(n.link()).isEqualTo("/providers/vsphere");
        assertThat(n.jobId()).isEqualTo("job_7");
    }

    @Test
    void anApprovalRequestNotifiesTheApproverGroupOnItsChannel() {
        NotificationProperties.Team approvers = new NotificationProperties.Team();
        approvers.setChannel("#data-approvals");
        props.getTeams().put("data-approvers", approvers);
        ObjectNode d = F.objectNode().put("jobId", "job_1").put("approvalId", "apr_1").put("workflow", "create-database").put("step", "approve")
                .put("group", "data-approvers").put("message", "Create db-orders in prod?");

        Notification n = listener.notificationFor(event("job.approval-requested", "job_1", NOW, d)).orElseThrow();

        assertThat(n.kind()).isEqualTo(Notification.APPROVAL_REQUESTED);
        assertThat(n.teams()).containsExactly("data-approvers");
        assertThat(n.channel()).isEqualTo("#data-approvals");
        assertThat(n.title()).isEqualTo("Approval needed: create-database");
        assertThat(n.message()).isEqualTo("Create db-orders in prod? Job job_1 waits for data-approvers at step approve.");
        assertThat(n.link()).isEqualTo("/jobs/job_1");
    }

    @Test
    void anEscalationNotifiesTheEscalationGroup() {
        ObjectNode d = F.objectNode().put("jobId", "job_1").put("approvalId", "apr_1").put("step", "approve").put("to", "platform-leads");

        Notification n = listener.notificationFor(event("job.approval-escalated", "job_1", NOW, d)).orElseThrow();

        assertThat(n.kind()).isEqualTo(Notification.APPROVAL_ESCALATED);
        assertThat(n.teams()).containsExactly("platform-leads");
        assertThat(n.severity()).isEqualTo("warning");
        assertThat(n.message()).contains("platform-leads can decide it now");
    }

    @Test
    void historyOlderThanTheFreshnessWindowAndOtherEventsNotifyNobody() {
        ObjectNode d = F.objectNode().put("jobId", "job_1").put("group", "data-approvers");

        assertThat(listener.notificationFor(event("job.approval-requested", "job_1", NOW.minus(Duration.ofHours(2)), d))).isEmpty();
        assertThat(listener.notificationFor(event("job.approval-requested", "job_1", NOW.minus(Duration.ofMinutes(5)), d))).isPresent();
        assertThat(listener.notificationFor(event("job.step", "job_1", NOW, d))).isEmpty();
        assertThat(listener.notificationFor(event("job.approval-requested", "job_1", NOW, F.objectNode().put("jobId", "job_1")))).isEmpty();
    }

    @Test
    void publishedEventsReachTheApproversInbox() throws InterruptedException {
        listener.subscribe();
        Identity ana = new Identity("ana", "Ana", null, List.of("data-approvers"), List.of(), false, Identity.Kind.USER);

        bus.publish("job.approval-requested", "job", "job_1", "platform",
                F.objectNode().put("jobId", "job_1").put("workflow", "create-database").put("group", "data-approvers"));

        List<Notification> seen = List.of();
        for (int i = 0; i < 100 && seen.isEmpty(); i++) {
            seen = store.visibleTo(ana);
            Thread.sleep(20);
        }
        assertThat(seen).singleElement().satisfies(n -> assertThat(n.kind()).isEqualTo(Notification.APPROVAL_REQUESTED));
        assertThat(Optional.ofNullable(store.visibleTo(new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of(), false,
                Identity.Kind.USER)))).hasValueSatisfying(l -> assertThat(l).isEmpty());
    }
}
