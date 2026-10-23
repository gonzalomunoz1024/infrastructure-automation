package com.iaf.console.bff.trust.adapters.inbound;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iaf.console.bff.trust.application.ServiceTokens;
import com.iaf.console.bff.trust.domain.ServiceTokenException;
import com.iaf.console.bff.trust.domain.ServiceTokenRequest;

import reactor.core.publisher.Mono;

/**
 * {@code POST /internal/v1/token}: a console service's token of its own (hub decision 23),
 * form-encoded as RFC 6749 has it, answered and refused in its shapes, never cached. In-cluster
 * only: the ingress routes {@code /auth}, {@code /console} and the JWKS, never {@code /internal}.
 */
@RestController
public class ServiceTokenController {

    public static final String PATH = "/internal/v1/token";

    private final ServiceTokens tokens;

    public ServiceTokenController(ServiceTokens tokens) {
        this.tokens = tokens;
    }

    /** RFC 6749 §5.1. */
    public record TokenBody(@JsonProperty("access_token") String accessToken, @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn, String audience) {
    }

    /** RFC 6749 §5.2. */
    public record ErrorBody(String error, @JsonProperty("error_description") String errorDescription) {
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TokenBody>> issue(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> tokens.issue(request(form)))
                .map(issued -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                        .body(new TokenBody(issued.token(), "Bearer", issued.expiresIn(), issued.audience())));
    }

    @ExceptionHandler(ServiceTokenException.class)
    public ResponseEntity<ErrorBody> refused(ServiceTokenException e) {
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(e.error(), e.getMessage()));
    }

    private static ServiceTokenRequest request(MultiValueMap<String, String> form) {
        return new ServiceTokenRequest(form.getFirst("grant_type"), form.getFirst("client_assertion_type"), form.getFirst("client_assertion"),
                form.getFirst("client_id"), form.get("audience"));
    }
}
