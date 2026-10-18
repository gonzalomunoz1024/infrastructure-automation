package com.iaf.platform.notifications.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.inbound.NotificationCommands;
import com.iaf.platform.notifications.ports.inbound.NotificationQueries;
import com.iaf.platform.notifications.ports.outbound.InboxStore;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sends each notification to every delivery adapter (the inbox, and chat or email when their
 * profiles are on) and serves the caller's inbox. A channel's configured teams join the audience,
 * so the inbox shows a {@code #vm-ops} message to the teams that follow {@code #vm-ops}.
 * Primary so that injecting a port resolves here, not to the REST adapter.
 */
@Slf4j
@Service
@Primary
public class NotificationHandler implements NotificationCommands, NotificationQueries {

    private record Outcome(String port, boolean delivered, boolean failed) {
    }

    private final List<NotificationPort> ports;
    private final InboxStore inbox;
    private final NotificationProperties props;
    private final Clock clock;

    public NotificationHandler(List<NotificationPort> ports, InboxStore inbox, NotificationProperties props, Clock clock) {
        this.ports = List.copyOf(ports);
        this.inbox = inbox;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Mono<Delivery> send(Notification notification) {
        Notification n = complete(notification);
        return Flux.fromIterable(ports)
                .flatMapSequential(p -> p.deliver(n)
                        .defaultIfEmpty(false)
                        .map(ok -> new Outcome(p.name(), ok, false))
                        .onErrorResume(e -> {
                            log.warn("notification {} ({}) was not delivered by {}: {}", n.id(), n.kind(), p.name(), e.getMessage());
                            return Mono.just(new Outcome(p.name(), false, true));
                        }))
                .collectList()
                .map(outcomes -> {
                    List<String> via = new ArrayList<>();
                    List<String> failed = new ArrayList<>();
                    for (Outcome o : outcomes) {
                        if (o.delivered()) {
                            via.add(o.port());
                        } else if (o.failed()) {
                            failed.add(o.port());
                        }
                    }
                    return new Delivery(n, List.copyOf(via), List.copyOf(failed));
                });
    }

    @Override
    public Mono<Inbox> inbox(Boolean unread) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            List<NotificationView> all = inbox.visibleTo(who).stream().map(n -> view(n, inbox.readBy(n.id(), who.id()))).toList();
            int unreadCount = (int) all.stream().filter(v -> !v.read()).count();
            List<NotificationView> items = unread == null ? all : all.stream().filter(v -> v.read() != unread).toList();
            return new Inbox(items, unreadCount);
        }));
    }

    @Override
    public Mono<NotificationView> markRead(String id) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            Notification n = inbox.get(id).filter(x -> x.addressedTo(who)).orElseThrow(() -> new NotFoundException("notification", id));
            inbox.markRead(id, who.id());
            return view(n, true);
        }));
    }

    /** An id, a time, and the audience the channel's configuration adds. */
    Notification complete(Notification n) {
        Notification out = n;
        if (out.id() == null || out.id().isBlank()) {
            out = out.withId(Ids.next("ntf"));
        }
        if (out.at() == null) {
            out = out.withAt(clock.instant());
        }
        NotificationProperties.Channel channel = props.channel(out.channel()).orElse(null);
        return channel == null ? out : out.withMoreTeams(channel.getTeams());
    }

    static NotificationView view(Notification n, boolean read) {
        return new NotificationView(n.id(), n.at(), n.kind(), n.severity(), n.title(), n.message(), n.link(), n.channel(), n.jobId(), n.workflow(),
                n.provider(), n.users(), n.teams(), read);
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }
}
