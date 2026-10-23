package com.iaf.console.bff.live.adapters.inbound;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.live.application.Live;
import com.iaf.console.bff.live.domain.LiveEvent;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Flux;

/** {@code GET /console/v1/events}: the account's live updates as server-sent events. */
@RestController
public class EventsController {

    private final Live live;

    public EventsController(Live live) {
        this.live = live;
    }

    @GetMapping(path = "/console/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<LiveEvent>> stream(
            @RequestParam(name = "wfrn", required = false) String wfrn,
            @RequestParam(name = "types", required = false) String types,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set("Cache-Control", "no-cache");
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
        Long after = parse(lastEventId);
        Set<String> wanted = types == null || types.isBlank() ? Set.of()
                : new LinkedHashSet<>(Arrays.stream(types.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList());
        return CurrentSession.required(exchange).flatMapMany(session -> live.stream(session, after, wfrn, wanted));
    }

    private static Long parse(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
