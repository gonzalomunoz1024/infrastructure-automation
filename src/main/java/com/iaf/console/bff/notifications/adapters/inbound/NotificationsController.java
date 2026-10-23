package com.iaf.console.bff.notifications.adapters.inbound;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.notifications.application.NotificationPreferences;
import com.iaf.console.bff.notifications.application.Notifications;
import com.iaf.console.bff.notifications.domain.SettingsView;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/**
 * The person's notifications (decision 32.2 and 32.3): {@code /console/v1/notifications} and its
 * {@code :read}, {@code /console/v1/notification-settings} (an ETag on every answer, {@code If-Match}
 * on a replace) and its {@code :test}.
 */
@RestController
public class NotificationsController {

    static final String FEED = "/console/v1/notifications";
    static final String SETTINGS = "/console/v1/notification-settings";

    private final Notifications notifications;
    private final NotificationPreferences preferences;

    public NotificationsController(Notifications notifications, NotificationPreferences preferences) {
        this.notifications = notifications;
        this.preferences = preferences;
    }

    @GetMapping(path = FEED, produces = "application/json")
    public Mono<Notifications.FeedPage> feed(@RequestParam(name = "unread", required = false) Boolean unread,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> notifications.feed(session, unread, kind, accountId, limit, after));
    }

    @PostMapping(path = FEED + ":read", produces = "application/json")
    public Mono<Notifications.Unread> read(@RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> notifications.markRead(session, body));
    }

    @GetMapping(path = SETTINGS, produces = "application/json")
    public Mono<ResponseEntity<SettingsView>> settings(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(preferences::get).map(NotificationsController::withEtag);
    }

    @PutMapping(path = SETTINGS, consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<SettingsView>> replace(@RequestBody(required = false) JsonNode body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> preferences.replace(session, ifMatch, body)).map(NotificationsController::withEtag);
    }

    @PostMapping(path = SETTINGS + ":test", produces = "application/json")
    public Mono<ResponseEntity<Notifications.TestSent>> test(@RequestBody(required = false) JsonNode body, ServerWebExchange exchange) {
        String channel = body == null ? null : body.path("channel").asText(null);
        return CurrentSession.required(exchange).flatMap(session -> notifications.sendTest(session, channel))
                .map(sent -> ResponseEntity.status(HttpStatus.ACCEPTED).body(sent));
    }

    private static ResponseEntity<SettingsView> withEtag(SettingsView view) {
        return ResponseEntity.ok().eTag("\"" + view.version() + "\"").header(HttpHeaders.CACHE_CONTROL, "private, no-cache").body(view);
    }
}
