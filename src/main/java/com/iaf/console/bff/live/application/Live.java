package com.iaf.console.bff.live.application;

import java.time.Duration;
import java.util.Set;

import org.springframework.http.codec.ServerSentEvent;

import com.iaf.console.bff.live.domain.LiveEvent;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.live.domain.Sequenced;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Flux;

/** The live stream of a session's account as server-sent events, narrowed to a resource or to event types, with heartbeats. */
public class Live {

    private final LiveHub hub;
    private final LiveProperties properties;

    public Live(LiveHub hub, LiveProperties properties) {
        this.hub = hub;
        this.properties = properties;
    }

    public LiveHub hub() {
        return hub;
    }

    public Flux<ServerSentEvent<LiveEvent>> stream(Session session, Long lastEventId, String wfrn, Set<String> types) {
        Flux<ServerSentEvent<LiveEvent>> events = hub.subscribe(session.accountId(), lastEventId)
                .filter(s -> matches(s, wfrn, types))
                .map(s -> ServerSentEvent.<LiveEvent>builder().id(Long.toString(s.seq())).event(s.event().type()).data(s.event()).build());
        Flux<ServerSentEvent<LiveEvent>> heartbeat = Flux.interval(properties.heartbeat(), properties.heartbeat())
                .map(tick -> ServerSentEvent.<LiveEvent>builder().comment("keepalive").build());
        return Flux.merge(events, heartbeat);
    }

    private static boolean matches(Sequenced s, String wfrn, Set<String> types) {
        LiveEvent event = s.event();
        if (LiveEvent.RESYNC.equals(event.type())) {
            return true;
        }
        if (wfrn != null && !wfrn.isBlank() && !s.global() && !wfrn.equals(event.wfrn())) {
            return false;
        }
        return types == null || types.isEmpty() || types.contains(event.type());
    }

    public Duration heartbeat() {
        return properties.heartbeat();
    }
}
