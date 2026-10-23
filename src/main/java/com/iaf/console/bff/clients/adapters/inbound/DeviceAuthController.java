package com.iaf.console.bff.clients.adapters.inbound;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iaf.console.bff.clients.application.ApiTokens;
import com.iaf.console.bff.clients.application.ClientsProperties;
import com.iaf.console.bff.clients.application.DeviceAuthorization;
import com.iaf.console.bff.clients.domain.OAuthException;

import reactor.core.publisher.Mono;

/**
 * The CLI and SDK's side of decision 31, form-encoded as OAuth has it and never cached:
 * {@code POST /auth/device} starts a sign-in (RFC 8628 §3.1), {@code POST /auth/token} redeems an
 * approved device code or a refresh token (RFC 8628 §3.4, RFC 6749 §6), {@code POST /auth/revoke}
 * signs out (RFC 7009). A refusal is RFC 6749 §5.2's {@code {error, error_description}}; a
 * parameter sent twice is {@code invalid_request} (RFC 6749 §3.1).
 */
@RestController
public class DeviceAuthController {

    private final DeviceAuthorization authorization;
    private final ApiTokens tokens;
    private final ClientsProperties properties;

    public DeviceAuthController(DeviceAuthorization authorization, ApiTokens tokens, ClientsProperties properties) {
        this.authorization = authorization;
        this.tokens = tokens;
        this.properties = properties;
    }

    /** RFC 8628 §3.2. */
    public record DeviceAuthorizationBody(@JsonProperty("device_code") String deviceCode, @JsonProperty("user_code") String userCode,
            @JsonProperty("verification_uri") String verificationUri, @JsonProperty("verification_uri_complete") String verificationUriComplete,
            @JsonProperty("expires_in") long expiresIn, long interval) {
    }

    /** RFC 6749 §5.1, with the seconds until the API session ends. */
    public record TokenBody(@JsonProperty("access_token") String accessToken, @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn, @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_in") long refreshExpiresIn, String scope) {
    }

    /** RFC 6749 §5.2. */
    public record ErrorBody(String error, @JsonProperty("error_description") String errorDescription) {
    }

    @PostMapping(path = "/auth/device", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<DeviceAuthorizationBody>> start(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(form -> authorization.start(single(form, "client_id"), RequestOrigin.of(exchange.getRequest(), properties.proxyHops())))
                .map(started -> noStore(ResponseEntity.ok()).body(new DeviceAuthorizationBody(started.deviceCode(), started.userCode(),
                        started.verificationUri(), started.verificationUriComplete(), started.expiresIn(), started.interval())));
    }

    @PostMapping(path = "/auth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TokenBody>> token(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(form -> tokens.exchange(new ApiTokens.TokenRequest(single(form, "grant_type"), single(form, "client_id"),
                        single(form, "device_code"), single(form, "refresh_token")), RequestOrigin.of(exchange.getRequest(), properties.proxyHops())))
                .map(issued -> noStore(ResponseEntity.ok()).body(new TokenBody(issued.accessToken(), "Bearer", issued.expiresIn(), issued.refreshToken(),
                        issued.refreshExpiresIn(), ApiTokens.SCOPE)));
    }

    /** RFC 7009 §2.2: 200 whatever the token was. */
    @PostMapping(path = "/auth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> revoke(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(form -> tokens.revoke(single(form, "token")))
                .then(Mono.fromSupplier(() -> noStore(ResponseEntity.ok()).<Void>build()));
    }

    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<ErrorBody> refused(OAuthException e) {
        return noStore(ResponseEntity.status(e.status())).contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(e.error(), e.getMessage()));
    }

    /** A form field's one value: absent or empty is null (RFC 6749 §3.1), twice is {@code invalid_request}. */
    static String single(MultiValueMap<String, String> form, String name) {
        List<String> values = form.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw OAuthException.invalidRequest(name + " is sent more than once");
        }
        String value = values.get(0);
        return value == null || value.isEmpty() ? null : value;
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
        return builder.cacheControl(CacheControl.noStore()).header("Pragma", "no-cache");
    }
}
