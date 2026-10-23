package com.iaf.console.bff.clients.adapters.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.bff.clients.application.DeviceAuthorization;
import com.iaf.console.bff.clients.domain.BrowserOnlyException;
import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.domain.DeviceGrantExpiredException;
import com.iaf.console.bff.clients.domain.UserCode;
import com.iaf.console.bff.common.web.BffExceptionHandler;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.errors.Problem;

import reactor.core.publisher.Mono;

/**
 * The web app's {@code #/device} page (decision 31): the grant a user code names, and the
 * person's decision on it in a signed-in browser. Approving needs a sign-in as fresh as a delete
 * does (the step-up 403); a grant is decided once (409 {@code device-grant.decided}) and never
 * after it expired (410 {@code device-grant.expired}); a code unknown, or ten minutes past its
 * expiry, is a 404. The decisions are browser-only: a bearer call is a 403 {@code browser.only}.
 */
@RestController
public class DeviceGrantsController {

    static final String PATH = "/console/v1/device-grants/{userCode}";

    private final DeviceAuthorization authorization;

    public DeviceGrantsController(DeviceAuthorization authorization) {
        this.authorization = authorization;
    }

    /** What the page shows: the code, the client, the status now, the times, and where the client asked from so the person can tell it is theirs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeviceGrantBody(String userCode, String clientId, String status, Instant requestedAt, Instant expiresAt, From requestedFrom,
            Instant decidedAt) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record From(String ip, String userAgent) {
        }
    }

    @GetMapping(path = PATH, produces = "application/json")
    public Mono<DeviceGrantBody> get(@PathVariable String userCode, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> authorization.find(userCode)).map(this::body);
    }

    @PostMapping(path = PATH + ":approve", produces = "application/json")
    public Mono<DeviceGrantBody> approve(@PathVariable String userCode, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> authorization.approve(session, userCode)).map(this::body);
    }

    @PostMapping(path = PATH + ":deny", produces = "application/json")
    public Mono<DeviceGrantBody> deny(@PathVariable String userCode, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> authorization.deny(session, userCode)).map(this::body);
    }

    @ExceptionHandler(DeviceGrantExpiredException.class)
    public ResponseEntity<BffExceptionHandler.CodedBody> expired(DeviceGrantExpiredException e, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.GONE).body(new BffExceptionHandler.CodedBody(DeviceGrantExpiredException.CODE, e.getMessage(),
                List.of(Problem.error(DeviceGrantExpiredException.CODE, null, e.getMessage())), Correlation.of(exchange),
                Map.of("expiresAt", e.expiresAt())));
    }

    @ExceptionHandler(BrowserOnlyException.class)
    public ResponseEntity<BffExceptionHandler.CodedBody> browserOnly(BrowserOnlyException e, ServerWebExchange exchange) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new BffExceptionHandler.CodedBody(BrowserOnlyException.CODE, e.getMessage(), null, Correlation.of(exchange), null));
    }

    private DeviceGrantBody body(DeviceGrant grant) {
        return new DeviceGrantBody(UserCode.display(grant.userCode()), grant.clientId(), authorization.statusOf(grant),
                grant.requestedAt(), grant.expiresAt(), new DeviceGrantBody.From(grant.requestedFrom().ip(), grant.requestedFrom().userAgent()),
                grant.decidedAt());
    }
}
