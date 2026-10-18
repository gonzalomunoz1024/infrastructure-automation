package com.iaf.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.notifications.adapters.outbound.InAppNotifications;
import com.iaf.platform.notifications.adapters.outbound.InMemoryInboxStore;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands.Delivery;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries.Inbox;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries.NotificationView;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NotificationHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Identity ANA = new Identity("ana", "Ana", "ana@example.com", List.of("team-data", "data-approvers"), List.of("ops"), false,
            Identity.Kind.USER);
    static final Identity MATEO = new Identity("mateo", "Mateo", "mateo@example.com", List.of("team-compute"), List.of("ops"), false,
            Identity.Kind.USER);
    static final Identity ADMIN = new Identity("root", "Root", "root@example.com", List.of(), List.of("admin"), true, Identity.Kind.USER);

    final InMemoryInboxStore store = new InMemoryInboxStore(100);
    final List<Notification> chat = new CopyOnWriteArrayList<>();
    final NotificationProperties props = new NotificationProperties();

    NotificationPort chatPort(boolean fail) {
        return new NotificationPort() {
            @Override
            public String name() {
                return "slack";
            }

            @Override
            public Mono<Boolean> deliver(Notification n) {
                if (fail) {
                    return Mono.error(new IllegalStateException("webhook answered 500"));
                }
                if (n.channel() == null) {
                    return Mono.just(false);
                }
                chat.add(n);
                return Mono.just(true);
            }
        };
    }

    NotificationHandler handler(NotificationPort... more) {
        List<NotificationPort> ports = new java.util.ArrayList<>();
        ports.add(new InAppNotifications(store));
        ports.addAll(List.of(more));
        return new NotificationHandler(ports, store, props, CLOCK);
    }

    static Notification toTeam(String team, String channel) {
        return Notification.builder().kind(Notification.NOTIFY).title("vm-restart: Tell the channel").message("web-01 restarted").channel(channel)
                .teams(List.of(team)).jobId("job_1").link("/jobs/job_1").build();
    }

    <T> T as(Identity who, Mono<T> mono) {
        return mono.contextWrite(ctx -> CurrentIdentity.with(ctx, who)).block(Duration.ofSeconds(5));
    }

    @Test
    void sendsToEveryAdapterAndSaysWhichDeliveredIt() {
        NotificationHandler handler = handler(chatPort(false));

        Delivery d = handler.send(toTeam("team-data", "#data-ops")).block(Duration.ofSeconds(5));

        assertThat(d.via()).containsExactly("inbox", "slack");
        assertThat(d.failed()).isEmpty();
        assertThat(d.notification().id()).startsWith("ntf_");
        assertThat(d.notification().at()).isEqualTo(NOW);
        assertThat(chat).singleElement().satisfies(n -> assertThat(n.channel()).isEqualTo("#data-ops"));
        assertThat(store.get(d.notification().id())).isPresent();
    }

    @Test
    void aFailingAdapterIsReportedAndDoesNotStopTheOthers() {
        NotificationHandler handler = handler(chatPort(true));

        Delivery d = handler.send(toTeam("team-data", "#data-ops")).block(Duration.ofSeconds(5));

        assertThat(d.via()).containsExactly("inbox");
        assertThat(d.failed()).containsExactly("slack");
    }

    @Test
    void theChannelsConfiguredTeamsJoinTheAudience() {
        NotificationProperties.Channel vmOps = new NotificationProperties.Channel();
        vmOps.setTeams(List.of("team-compute"));
        props.getChannels().put("vm-ops", vmOps);
        NotificationHandler handler = handler();

        Delivery d = handler.send(toTeam("team-data", "#VM-Ops")).block(Duration.ofSeconds(5));

        assertThat(d.notification().teams()).containsExactly("team-data", "team-compute");
        assertThat(as(MATEO, handler.inbox(null)).items()).hasSize(1);
    }

    @Test
    void theInboxShowsWhatIsAddressedToTheCallerOrTheirTeamsNewestFirst() {
        NotificationHandler handler = handler();
        handler.send(toTeam("team-data", null).withAt(NOW.minusSeconds(60)).withTitle("older")).block();
        handler.send(Notification.builder().kind(Notification.NOTIFY).title("for ana").users(List.of("ana")).build()).block();
        handler.send(toTeam("team-compute", null).withTitle("for compute")).block();

        Inbox ana = as(ANA, handler.inbox(null));
        Inbox mateo = as(MATEO, handler.inbox(null));
        Inbox admin = as(ADMIN, handler.inbox(null));

        assertThat(ana.items()).extracting(NotificationView::title).containsExactly("for ana", "older");
        assertThat(ana.unread()).isEqualTo(2);
        assertThat(mateo.items()).extracting(NotificationView::title).containsExactly("for compute");
        assertThat(admin.items()).isEmpty();
    }

    @Test
    void readingIsPerPersonAndTheUnreadFilterFollowsIt() {
        NotificationHandler handler = handler();
        Delivery d = handler.send(toTeam("data-approvers", null)).block();
        handler.send(toTeam("data-approvers", null).withTitle("second")).block();
        Identity gonzalo = new Identity("gonzalo", "Gonzalo", null, List.of("data-approvers"), List.of(), true, Identity.Kind.USER);

        NotificationView read = as(ANA, handler.markRead(d.notification().id()));

        assertThat(read.read()).isTrue();
        Inbox anaUnread = as(ANA, handler.inbox(true));
        assertThat(anaUnread.items()).extracting(NotificationView::title).containsExactly("second");
        assertThat(anaUnread.unread()).isEqualTo(1);
        assertThat(as(ANA, handler.inbox(false)).items()).extracting(NotificationView::id).containsExactly(d.notification().id());
        assertThat(as(gonzalo, handler.inbox(true)).unread()).isEqualTo(2);
    }

    @Test
    void markingSomethingTheCallerCannotSeeIsNotFound() {
        NotificationHandler handler = handler();
        Delivery d = handler.send(toTeam("team-data", null)).block();

        StepVerifier.create(handler.markRead(d.notification().id()).contextWrite(ctx -> CurrentIdentity.with(ctx, MATEO)))
                .expectError(NotFoundException.class).verify(Duration.ofSeconds(5));
        StepVerifier.create(handler.markRead("ntf_nope").contextWrite(ctx -> CurrentIdentity.with(ctx, ANA)))
                .expectError(NotFoundException.class).verify(Duration.ofSeconds(5));
    }

    @Test
    void aNotificationForNobodyIsNotKeptInTheInbox() {
        NotificationHandler handler = handler(chatPort(false));

        Delivery d = handler.send(Notification.builder().kind(Notification.NOTIFY).message("hello").channel("#general").build()).block();

        assertThat(d.via()).containsExactly("slack");
        assertThat(store.get(d.notification().id())).isEmpty();
    }

    @Test
    void theInboxKeepsOnlyTheNewest() {
        InMemoryInboxStore small = new InMemoryInboxStore(2);
        NotificationHandler handler = new NotificationHandler(List.of(new InAppNotifications(small)), small, props, CLOCK);
        handler.send(toTeam("team-data", null).withTitle("one")).block();
        handler.send(toTeam("team-data", null).withTitle("two")).block();
        handler.send(toTeam("team-data", null).withTitle("three")).block();

        assertThat(as(ANA, handler.inbox(null)).items()).extracting(NotificationView::title).containsExactly("three", "two");
    }
}
