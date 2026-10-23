package com.iaf.console.bff.session.adapters.inbound;

import java.time.Duration;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.session.application.SessionProperties;

/** Builds and reads the session and login-ticket cookies with the configured flags. */
@Component
public class SessionCookies {

    private final SessionProperties properties;

    public SessionCookies(SessionProperties properties) {
        this.properties = properties;
    }

    public String sessionValue(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(properties.cookieName());
        return cookie == null ? null : cookie.getValue();
    }

    public String ticketValue(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(properties.ticketCookieName());
        return cookie == null ? null : cookie.getValue();
    }

    public ResponseCookie session(String id, Duration maxAge) {
        return base(properties.cookieName(), id, properties.cookiePath()).maxAge(maxAge).build();
    }

    public ResponseCookie clearSession() {
        return base(properties.cookieName(), "", properties.cookiePath()).maxAge(0).build();
    }

    public ResponseCookie ticket(String sealed) {
        return base(properties.ticketCookieName(), sealed, "/auth").maxAge(properties.ticketTtl()).build();
    }

    public ResponseCookie clearTicket() {
        return base(properties.ticketCookieName(), "", "/auth").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(properties.secure()).sameSite(properties.sameSite()).path(path);
    }
}
