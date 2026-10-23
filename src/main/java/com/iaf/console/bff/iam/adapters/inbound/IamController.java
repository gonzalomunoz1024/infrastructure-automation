package com.iaf.console.bff.iam.adapters.inbound;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.iam.application.IamPaths;
import com.iaf.console.bff.iam.application.IamProxy;
import com.iaf.console.bff.iam.application.ProxiedResponse;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * {@code /console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}...},
 * decision 34's {@code guardrails} (read only), {@code policy-simulations} (POST only),
 * {@code access-reviews} and {@code elevations}, the organization's {@code quota-rules} and
 * {@code elevation-rules} (read only), and {@code /console/v1/access-requests...}, served through
 * the identity service for the session's account ({@link IamPaths} says which paths exist). Writes
 * go through the CSRF filter like every other write.
 */
@RestController
public class IamController {

    private final IamProxy proxy;

    public IamController(IamProxy proxy) {
        this.proxy = proxy;
    }

    @RequestMapping(path = {"/console/v1/iam/{resource}", "/console/v1/iam/{resource}/**"})
    public Mono<ResponseEntity<byte[]>> iam(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String rest = path.substring("/console/v1/iam/".length());
        String resource = IamPaths.collectionOf(rest);
        if (!IamPaths.COLLECTIONS.contains(resource)) {
            return Mono.error(new NotFoundException("no IAM resource " + resource));
        }
        HttpStatus refusal = IamPaths.refusal(exchange.getRequest().getMethod(), rest);
        if (refusal == HttpStatus.NOT_FOUND) {
            return Mono.error(new NotFoundException("no IAM path " + rest));
        }
        if (refusal != null) {
            return Mono.just(ResponseEntity.status(refusal).contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"" + exchange.getRequest().getMethod().name() + " is not served on " + resource + "\"}")
                            .getBytes(StandardCharsets.UTF_8)));
        }
        return forward(exchange, rest);
    }

    @RequestMapping(path = {"/console/v1/access-requests", "/console/v1/access-requests/**"})
    public Mono<ResponseEntity<byte[]>> accessRequests(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String rest = path.substring("/console/v1/".length());
        return forward(exchange, rest);
    }

    private Mono<ResponseEntity<byte[]>> forward(ServerWebExchange exchange, String rest) {
        HttpMethod method = exchange.getRequest().getMethod();
        return CurrentSession.required(exchange).flatMap(session -> body(exchange)
                .flatMap(body -> proxy.forward(session, method, rest, exchange.getRequest().getURI().getRawQuery(), exchange.getRequest().getHeaders(), body)))
                .map(IamController::toEntity);
    }

    /** The request's body, empty when it has none. */
    static Mono<byte[]> body(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0]);
    }

    static ResponseEntity<byte[]> toEntity(ProxiedResponse response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status());
        if (response.etag() != null) {
            builder.header(HttpHeaders.ETAG, response.etag());
        }
        if (response.body() == null || response.body().length == 0) {
            return builder.build();
        }
        return builder.contentType(response.contentType()).body(response.body());
    }
}
