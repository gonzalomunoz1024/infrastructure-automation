package com.iaf.console.fake.common;

import java.net.URI;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.kit.errors.Errors;
import com.iaf.console.kit.errors.Problem;

import reactor.core.publisher.Mono;

/** Small helpers for the functional routes: typed query parameters, JSON answers and error bodies. */
public final class Http {

    public static final String LAST_EVENT_ID = "Last-Event-ID";

    private Http() {
    }

    public static Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    public static Mono<ServerResponse> json(int status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    public static Mono<ServerResponse> accepted(Object body) {
        return json(202, body);
    }

    public static Mono<ServerResponse> created(Object body) {
        return json(201, body);
    }

    public static Mono<ServerResponse> noContent() {
        return ServerResponse.noContent().build();
    }

    public static Mono<ServerResponse> error(int status, String error) {
        return json(status, Errors.of(error));
    }

    public static Mono<ServerResponse> error(int status, String error, java.util.List<Problem> problems) {
        return json(status, Errors.withProblems(error, problems));
    }

    public static Optional<String> query(ServerRequest request, String name) {
        return request.queryParam(name).map(String::trim).filter(s -> !s.isEmpty());
    }

    public static Optional<Integer> intQuery(ServerRequest request, String name) {
        return query(request, name).map(value -> {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest(name + " must be an integer");
            }
        });
    }

    public static Optional<Long> longQuery(ServerRequest request, String name) {
        return query(request, name).map(value -> {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException e) {
                throw ApiException.badRequest(name + " must be an integer");
            }
        });
    }

    /** A paging limit between 1 and {@code max}; absent when the caller sent none. */
    public static Optional<Integer> limit(ServerRequest request, int max) {
        return intQuery(request, "limit").map(limit -> {
            if (limit < 1 || limit > max) {
                throw ApiException.badRequest("limit must be between 1 and " + max);
            }
            return limit;
        });
    }

    public static String header(ServerRequest request, String name) {
        return request.headers().firstHeader(name);
    }

    /** The request URL without its {@code after} parameter, the base of a next-page link. */
    public static String urlWithoutCursor(ServerRequest request) {
        URI uri = request.uri();
        StringBuilder url = new StringBuilder(uri.getRawPath());
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            StringBuilder kept = new StringBuilder();
            for (String pair : query.split("&")) {
                if (pair.startsWith("after=") || pair.equals("after")) {
                    continue;
                }
                kept.append(kept.isEmpty() ? "" : "&").append(pair);
            }
            if (!kept.isEmpty()) {
                url.append('?').append(kept);
            }
        }
        return url.toString();
    }

    /** The Link header of the next page: the request URL with the new cursor. */
    public static String nextLink(ServerRequest request, String cursor) {
        String base = urlWithoutCursor(request);
        return "<" + base + (base.contains("?") ? "&" : "?") + "after=" + cursor + ">; rel=\"next\"";
    }

    /**
     * Where a stream starts: after {@code Last-Event-ID} when the client reconnects, after
     * {@code after} when it asks, else live only.
     */
    public static long resumeFrom(ServerRequest request, long liveFrom) {
        String lastEventId = header(request, LAST_EVENT_ID);
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Long.parseLong(lastEventId.trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Last-Event-ID must be a sequence");
            }
        }
        return longQuery(request, "after").orElse(liveFrom);
    }

    public static HttpStatus status(int code) {
        return HttpStatus.valueOf(code);
    }
}
