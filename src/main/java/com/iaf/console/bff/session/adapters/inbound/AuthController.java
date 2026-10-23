package com.iaf.console.bff.session.adapters.inbound;

import java.net.URI;
import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Login;
import com.iaf.console.bff.session.application.SessionProperties;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.ReturnTo;

import reactor.core.publisher.Mono;

/** The sign-in endpoints of the contract: start, callback and logout. */
@RestController
public class AuthController {

    private final Login login;
    private final Sessions sessions;
    private final SessionCookies cookies;
    private final SessionProperties properties;

    public AuthController(Login login, Sessions sessions, SessionCookies cookies, SessionProperties properties) {
        this.login = login;
        this.sessions = sessions;
        this.cookies = cookies;
        this.properties = properties;
    }

    @GetMapping("/auth/login")
    public Mono<ResponseEntity<Void>> start(
            @RequestParam(name = "returnTo", required = false) String returnTo,
            @RequestParam(name = "login_hint", required = false) String loginHint,
            @RequestParam(name = "stepUp", required = false, defaultValue = "false") boolean stepUp,
            ServerWebExchange exchange) {
        return login.start(returnTo, loginHint, stepUp, CurrentSession.of(exchange))
                .map(started -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(started.redirectTo()))
                        .header("Set-Cookie", cookies.ticket(started.ticketCookie()).toString())
                        .header("Cache-Control", "no-store")
                        .build());
    }

    @GetMapping("/auth/callback")
    public Mono<ResponseEntity<Void>> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription,
            ServerWebExchange exchange) {
        if (error != null && !error.isBlank()) {
            return Mono.error(new IllegalArgumentException("the identity provider answered " + error
                    + (errorDescription == null || errorDescription.isBlank() ? "" : ": " + errorDescription)));
        }
        return login.complete(code, state, cookies.ticketValue(exchange)).map(completed -> {
            ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(ReturnTo.sanitize(completed.returnTo())))
                    .header("Cache-Control", "no-store")
                    .header("Set-Cookie", cookies.clearTicket().toString());
            if (completed.newSession()) {
                Duration maxAge = Duration.between(completed.session().createdAt(), completed.session().expiresAt());
                response.header("Set-Cookie", cookies.session(completed.session().id(), maxAge).toString());
            }
            return response.build();
        });
    }

    @PostMapping("/auth/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        Mono<Void> ended = CurrentSession.of(exchange).map(sessions::logout).orElse(Mono.empty());
        return ended.thenReturn(ResponseEntity.noContent()
                .header("Set-Cookie", cookies.clearSession().toString())
                .header("Cache-Control", "no-store")
                .build());
    }

    SessionProperties properties() {
        return properties;
    }
}
