package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import reactor.core.publisher.Flux;

/** A browser's view of the console: signs in through the demo identity provider and calls the API with the session cookie and the CSRF token. */
public final class Console {

    public static final String SESSION_COOKIE = "console_session";
    public static final String TICKET_COOKIE = "console_login";
    public static final String CSRF_HEADER = "X-Csrf-Token";
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final WebClient client;

    public Console(String base) {
        this.client = WebClient.builder().baseUrl(base).codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build();
    }

    /** What the server answered. */
    public record Reply(int status, HttpHeaders headers, MultiValueMap<String, ResponseCookie> cookies, String body) {

        public JsonNode json() {
            try {
                return body == null || body.isBlank() ? MissingNode.getInstance() : JSON.readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String location() {
            return headers.getFirst(HttpHeaders.LOCATION);
        }

        public String cookie(String name) {
            ResponseCookie cookie = cookies.getFirst(name);
            return cookie == null ? null : cookie.getValue();
        }
    }

    /** A signed-in person: the session cookie, the CSRF token and the session view. */
    public record Signed(String user, String cookie, String csrf, JsonNode view) {

        public String accountId() {
            return view.path("account").path("accountId").asText();
        }
    }

    public Reply call(WebClient.RequestHeadersSpec<?> spec) {
        return spec.exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                .map(body -> new Reply(r.statusCode().value(), r.headers().asHttpHeaders(), r.cookies(), body))).block();
    }

    public Reply get(String path, Signed as) {
        return get(path, as, Map.of());
    }

    public Reply get(String path, Signed as, Map<String, String> headers) {
        WebClient.RequestHeadersSpec<?> spec = client.get().uri(path);
        if (as != null) {
            spec = spec.cookie(SESSION_COOKIE, as.cookie());
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            spec = spec.header(h.getKey(), h.getValue());
        }
        return call(spec);
    }

    public Reply post(String path, Signed as, Object body) {
        return send(HttpMethod.POST, path, as, body, Map.of());
    }

    public Reply put(String path, Signed as, Object body) {
        return send(HttpMethod.PUT, path, as, body, Map.of());
    }

    /** Sends a write with the session cookie and the CSRF token; {@code headers} may override or drop (empty value) the token. */
    public Reply send(HttpMethod method, String path, Signed as, Object body, Map<String, String> headers) {
        WebClient.RequestBodySpec spec = client.method(method).uri(path);
        if (as != null) {
            spec = spec.cookie(SESSION_COOKIE, as.cookie());
            if (!headers.containsKey(CSRF_HEADER)) {
                spec = spec.header(CSRF_HEADER, as.csrf());
            }
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (!h.getValue().isEmpty()) {
                spec = spec.header(h.getKey(), h.getValue());
            }
        }
        if (body == null) {
            return call(spec);
        }
        return call(spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }

    /** Runs the sign-in up to the callback and returns the callback's answer, whatever it is. */
    public Reply attemptSignIn(String user, String returnTo, Signed current, boolean stepUp) {
        // template variables, so a returnTo with a fragment or a percent sign is encoded once
        WebClient.RequestHeadersSpec<?> start = client.get().uri(b -> b.path("/auth/login").queryParam("returnTo", "{r}").queryParam("login_hint", "{u}")
                .queryParam("stepUp", "{s}").build(returnTo, user, stepUp));
        if (current != null) {
            start = start.cookie(SESSION_COOKIE, current.cookie());
        }
        Reply started = call(start);
        assertEquals(302, started.status(), "login start: " + started.body());
        String ticket = started.cookie(TICKET_COOKIE);
        assertNotNull(ticket, "the login ticket cookie");
        Reply idp = call(client.get().uri(URI.create(started.location())));
        assertEquals(302, idp.status(), "demo idp: " + idp.body());
        WebClient.RequestHeadersSpec<?> callback = client.get().uri(URI.create(idp.location())).cookie(TICKET_COOKIE, ticket);
        if (current != null) {
            callback = callback.cookie(SESSION_COOKIE, current.cookie());
        }
        return call(callback);
    }

    public Signed signIn(String user) {
        Reply done = attemptSignIn(user, "/", null, false);
        assertEquals(302, done.status(), "callback: " + done.body());
        String cookie = done.cookie(SESSION_COOKIE);
        assertNotNull(cookie, "the session cookie");
        return session(user, cookie);
    }

    /** The session view of a cookie, as a signed-in person. */
    public Signed session(String user, String cookie) {
        Reply view = get("/console/v1/session", new Signed(user, cookie, null, null));
        assertEquals(200, view.status(), "session view: " + view.body());
        JsonNode json = view.json();
        return new Signed(user, cookie, json.path("csrfToken").asText(), json);
    }

    public Flux<ServerSentEvent<JsonNode>> events(Signed as, String lastEventId, String query) {
        WebClient.RequestHeadersSpec<?> spec = client.get().uri("/console/v1/events" + (query == null ? "" : "?" + query))
                .accept(MediaType.TEXT_EVENT_STREAM).cookie(SESSION_COOKIE, as.cookie());
        if (lastEventId != null) {
            spec = spec.header("Last-Event-ID", lastEventId);
        }
        return spec.retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<JsonNode>>() {
        });
    }
}
