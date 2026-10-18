package com.iaf.platform.notifications.adapters.inbound;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.notifications.adapters.outbound.InAppNotifications;
import com.iaf.platform.notifications.adapters.outbound.InMemoryInboxStore;
import com.iaf.platform.notifications.application.NotificationHandler;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;

class RestControllerNotificationAdapterTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    static final Identity ANA = new Identity("ana", "Ana", null, List.of("data-approvers"), List.of(), false, Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of(), false, Identity.Kind.USER);

    final InMemoryInboxStore store = new InMemoryInboxStore(100);
    final NotificationHandler handler = new NotificationHandler(List.of(new InAppNotifications(store)), store, new NotificationProperties(), CLOCK);
    final RestControllerNotificationAdapter adapter = new RestControllerNotificationAdapter(handler);

    static WebFilter identity(Identity who) {
        return (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, who);
            return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
        };
    }

    WebTestClient client(Identity who) {
        return WebTestClient.bindToController(adapter).controllerAdvice(new GlobalExceptionHandler()).webFilter(identity(who)).build();
    }

    String approvalFor(String team) {
        return handler.send(Notification.builder().kind(Notification.APPROVAL_REQUESTED).title("Approval needed: create-database")
                .message("Job job_1 waits for " + team + ".").link("/jobs/job_1").teams(List.of(team)).jobId("job_1").build())
                .block().notification().id();
    }

    @Test
    void listsTheCallersNotificationsWithTheUnreadCount() {
        String id = approvalFor("data-approvers");
        approvalFor("team-compute");

        client(ANA).get().uri("/api/v1/notifications").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.unread").isEqualTo(1)
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(id)
                .jsonPath("$.items[0].kind").isEqualTo("approval.requested")
                .jsonPath("$.items[0].link").isEqualTo("/jobs/job_1")
                .jsonPath("$.items[0].read").isEqualTo(false);
    }

    @Test
    void markingReadIsPerCallerAndFiltersTheUnreadList() {
        String id = approvalFor("data-approvers");

        client(ANA).post().uri("/api/v1/notifications/" + id + ":read").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.read").isEqualTo(true);

        client(ANA).get().uri("/api/v1/notifications?unread=true").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(0).jsonPath("$.unread").isEqualTo(0);
        client(ANA).get().uri("/api/v1/notifications?unread=false").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.items[0].id").isEqualTo(id);
        client(MATEO).post().uri("/api/v1/notifications/" + id + ":read").exchange().expectStatus().isNotFound();
    }
}
