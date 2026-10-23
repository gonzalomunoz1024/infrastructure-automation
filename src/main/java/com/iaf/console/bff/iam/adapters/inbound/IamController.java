package com.iaf.console.bff.iam.adapters.inbound;

import java.util.Set;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.iam.application.IamProxy;
import com.iaf.console.bff.iam.application.ProxiedResponse;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/**
 * {@code /console/v1/iam/{users,groups,roles,policies,access-keys,quotas,quota-requests}...} and
 * {@code /console/v1/access-requests...}, served through the identity service for the session's
 * account. Writes go through the CSRF filter like every other write.
 */
@RestController
public class IamController {

    private static final Set<String> RESOURCES = Set.of("users", "groups", "roles", "policies", "access-keys", "quotas", "quota-requests");

    private final IamProxy proxy;

    public IamController(IamProxy proxy) {
        this.proxy = proxy;
    }

    @RequestMapping(path = {"/console/v1/iam/{resource}", "/console/v1/iam/{resource}/**"})
    public Mono<ResponseEntity<byte[]>> iam(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String rest = path.substring("/console/v1/iam/".length());
        String resource = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest.replaceAll(":.*$", "");
        if (!RESOURCES.contains(resource)) {
            return Mono.error(new com.iaf.console.kit.errors.NotFoundException("no IAM resource " + resource));
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
        return CurrentSession.required(exchange).flatMap(session -> DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> proxy.forward(session, method, rest, exchange.getRequest().getURI().getRawQuery(), exchange.getRequest().getHeaders(), body)))
                .map(IamController::toEntity);
    }

    private static ResponseEntity<byte[]> toEntity(ProxiedResponse response) {
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
