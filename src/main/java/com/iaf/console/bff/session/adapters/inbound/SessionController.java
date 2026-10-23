package com.iaf.console.bff.session.adapters.inbound;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.access.application.AccountActions;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.SessionView;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/** The session the web app reads and switches, and the accounts it may switch to. */
@RestController
public class SessionController {

    private final Sessions sessions;
    private final AccountActions actions;

    public SessionController(Sessions sessions, AccountActions actions) {
        this.sessions = sessions;
        this.actions = actions;
    }

    @GetMapping(path = "/console/v1/session", produces = "application/json")
    public Mono<SessionView> get(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(this::view);
    }

    /** The body of a switch: either field may be absent. */
    public record Switch(String accountId, String region) {
    }

    @PutMapping(path = "/console/v1/session", consumes = "application/json", produces = "application/json")
    public Mono<SessionView> put(@RequestBody Switch body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange)
                .flatMap(session -> sessions.switchTo(session, body.accountId(), body.region()))
                .flatMap(this::view);
    }

    /** The session as the web app reads it, with the account's console actions decided when the identity service answers. */
    private Mono<SessionView> view(Session session) {
        SessionView view = sessions.view(session);
        return actions.decide(session).map(view::withDecisions).defaultIfEmpty(view);
    }

    @GetMapping(path = "/console/v1/accounts", produces = "application/json")
    public Mono<Map<String, List<SessionView.Account>>> accounts(ServerWebExchange exchange) {
        return CurrentSession.required(exchange).map(session -> Map.of("items", session.accounts().stream()
                .map(a -> new SessionView.Account(a.accountId(), a.name(), a.status(), a.defaultRegion(), a.roles(), a.groups()))
                .toList()));
    }
}
