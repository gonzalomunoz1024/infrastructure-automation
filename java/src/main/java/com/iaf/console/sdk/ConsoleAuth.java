package com.iaf.console.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Signing in to the BFF with the OAuth 2.0 device authorization grant (RFC 8628), refreshing with
 * rotating refresh tokens, and revoking (RFC 7009), as decision 31.1 of the hub has them:
 *
 * <pre>
 *   POST /auth/device  client_id                                 the device and user codes
 *   POST /auth/token   grant_type=urn:ietf:params:oauth:grant-type:device_code, device_code, client_id
 *   POST /auth/token   grant_type=refresh_token, refresh_token, client_id
 *   POST /auth/revoke  token, token_type_hint=refresh_token
 * </pre>
 *
 * <p>All three take forms and answer RFC 6749 §5.2 errors, thrown as {@link OAuthException}. The
 * device endpoints are not in the pinned contract yet: this follows the RFC and the decision. A
 * connection failure is an {@link UncheckedIOException}.
 */
public final class ConsoleAuth {
    public static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    /** The client id of programs that use the SDK; the CLI signs in as {@code console-cli}. */
    public static final String SDK_CLIENT_ID = "console-sdk";

    /** Told after each poll that is still pending, with the interval in force. */
    @FunctionalInterface
    public interface PollListener {
        void pending(int polls, Duration interval, boolean slowDown);
    }

    private final String endpoint;
    private final String clientId;
    private final HttpClient http;
    private final String userAgent;
    private final Duration timeout;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    private ConsoleAuth(Builder b) {
        this.endpoint = ConsoleClient.trimEndpoint(Objects.requireNonNull(b.endpoint, "endpoint"));
        this.clientId = b.clientId;
        this.http = b.http != null ? b.http : ConsoleClient.defaultHttpClient();
        this.userAgent = b.userAgent;
        this.timeout = b.timeout;
        this.clock = b.clock;
        this.sleeper = b.sleeper;
    }

    /** The sign-in at the BFF at {@code endpoint}, such as {@code https://console.example.com}. */
    public static Builder builder(String endpoint) {
        return new Builder(endpoint);
    }

    public String endpoint() {
        return endpoint;
    }

    public String clientId() {
        return clientId;
    }

    /** RFC 8628 §3.1: asks the BFF for a device code and a user code. */
    public DeviceAuthorization startDeviceAuthorization() {
        return startDeviceAuthorization(null);
    }

    /** RFC 8628 §3.1, asking for {@code scope}. */
    public DeviceAuthorization startDeviceAuthorization(String scope) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        if (scope != null) form.put("scope", scope);
        Reply reply = once("/auth/device", form);
        if (!reply.ok()) throw reply.error();
        JsonNode b = reply.json;
        String deviceCode = text(b, "device_code");
        String userCode = text(b, "user_code");
        String verificationUri = text(b, "verification_uri");
        if (deviceCode == null || userCode == null || verificationUri == null) {
            throw new OAuthException(reply.status, "invalid_response",
                    "the device authorization answer has no device_code, user_code or verification_uri");
        }
        Duration expiresIn = seconds(b, "expires_in", Duration.ofMinutes(10));
        return new DeviceAuthorization(deviceCode, userCode, verificationUri, text(b, "verification_uri_complete"),
                expiresIn, seconds(b, "interval", Duration.ofSeconds(5)), clock.instant().plus(expiresIn));
    }

    /** {@link #pollDeviceAuthorization(DeviceAuthorization, PollListener)} without a listener. */
    public TokenSet pollDeviceAuthorization(DeviceAuthorization authorization) throws InterruptedException {
        return pollDeviceAuthorization(authorization, null);
    }

    /**
     * RFC 8628 §3.4 and §3.5: polls the token endpoint until the person approves or denies the
     * grant, or it expires. Waits {@code interval} before each poll; {@code slow_down} adds 5
     * seconds to the interval for this and every later poll; a connection failure or a 5xx doubles
     * it. Throws an {@link OAuthException}: {@code access_denied}, {@code expired_token}, or what
     * else the BFF answered.
     */
    public TokenSet pollDeviceAuthorization(DeviceAuthorization authorization, PollListener listener)
            throws InterruptedException {
        Duration interval = authorization.interval().compareTo(Duration.ofSeconds(1)) < 0
                ? Duration.ofSeconds(1)
                : authorization.interval();
        int polls = 0;
        // the BFF judges a poll that lands just past the expiry; no wait starts after it
        while (clock.instant().isBefore(authorization.expiresAt())) {
            sleeper.sleep(interval);
            polls++;
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", DEVICE_CODE_GRANT);
            form.put("device_code", authorization.deviceCode());
            form.put("client_id", clientId);
            Reply reply;
            try {
                reply = post("/auth/token", form);
            } catch (IOException e) {
                interval = interval.multipliedBy(2); // §3.5: back off on a connection failure
                continue;
            }
            if (reply.ok()) return tokenSet(reply, clientId);
            String error = text(reply.json, "error");
            if ("authorization_pending".equals(error)) {
                if (listener != null) listener.pending(polls, interval, false);
                continue;
            }
            if ("slow_down".equals(error)) {
                interval = interval.plusSeconds(5);
                if (listener != null) listener.pending(polls, interval, true);
                continue;
            }
            if (error == null && reply.status >= 500) {
                interval = interval.multipliedBy(2);
                continue;
            }
            throw reply.error();
        }
        throw new OAuthException(400, "expired_token", "the code expired before the sign-in was approved");
    }

    /** Starts a device grant, hands its codes to {@code onCode} (to show them), and polls until it is decided. */
    public TokenSet deviceLogin(Consumer<DeviceAuthorization> onCode) throws InterruptedException {
        return deviceLogin(onCode, null);
    }

    public TokenSet deviceLogin(Consumer<DeviceAuthorization> onCode, PollListener listener) throws InterruptedException {
        DeviceAuthorization authorization = startDeviceAuthorization();
        onCode.accept(authorization);
        return pollDeviceAuthorization(authorization, listener);
    }

    /**
     * Renews the access token. The BFF rotates the refresh token: keep the new one before
     * anything else, because presenting the old one again revokes the whole API session.
     */
    public TokenSet refresh(String refreshToken) {
        return refresh(refreshToken, null);
    }

    /** Renews the access token of tokens issued to {@code tokenClientId} (this client's id when null). */
    public TokenSet refresh(String refreshToken, String tokenClientId) {
        String client = tokenClientId != null ? tokenClientId : clientId;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", Objects.requireNonNull(refreshToken, "refreshToken"));
        form.put("client_id", client);
        Reply reply = once("/auth/token", form);
        if (!reply.ok()) throw reply.error();
        return tokenSet(reply, client);
    }

    /** RFC 7009: revokes the API session the refresh token belongs to. The BFF answers 200 always. */
    public void revoke(String token) {
        revoke(token, "refresh_token");
    }

    public void revoke(String token, String tokenTypeHint) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", Objects.requireNonNull(token, "token"));
        form.put("token_type_hint", tokenTypeHint);
        form.put("client_id", clientId);
        Reply reply = once("/auth/revoke", form);
        if (!reply.ok()) throw reply.error();
    }

    // -----------------------------------------------------------------------------------------

    private record Reply(int status, JsonNode json, String raw) {
        boolean ok() {
            return status / 100 == 2;
        }

        OAuthException error() {
            String error = text(json, "error");
            String description = text(json, "error_description");
            if (description == null && json == null && raw != null && !raw.isBlank()) {
                description = raw.length() > 200 ? raw.substring(0, 200) : raw;
            }
            return new OAuthException(status, error != null ? error : "http." + status, description);
        }
    }

    private Reply post(String path, Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Http.form(form)));
        if (userAgent != null) request.header("User-Agent", userAgent);
        HttpResponse<String> res = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String body = res.body();
        JsonNode json = null;
        if (body != null && !body.isBlank()) {
            try {
                json = mapper.readTree(body);
            } catch (IOException e) {
                // not JSON: keep the text for the error
            }
        }
        return new Reply(res.statusCode(), json, body);
    }

    /** One request, its failures unchecked: an interrupt keeps the thread's flag. */
    private Reply once(String path, Map<String, String> form) {
        try {
            return post(path, form);
        } catch (IOException e) {
            throw new UncheckedIOException("POST " + endpoint + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("interrupted: POST " + endpoint + path);
            interrupted.initCause(e);
            throw new UncheckedIOException(interrupted);
        }
    }

    private TokenSet tokenSet(Reply reply, String client) {
        JsonNode b = reply.json;
        String accessToken = text(b, "access_token");
        if (accessToken == null) throw new OAuthException(reply.status, "invalid_response", "the token answer has no access_token");
        Instant now = clock.instant();
        Duration expiresIn = seconds(b, "expires_in", null);
        Duration refreshExpiresIn = seconds(b, "refresh_expires_in", null);
        return new TokenSet(
                accessToken,
                text(b, "token_type") != null ? text(b, "token_type") : "Bearer",
                text(b, "refresh_token"),
                expiresIn == null ? null : now.plus(expiresIn),
                refreshExpiresIn == null ? null : now.plus(refreshExpiresIn),
                text(b, "scope"),
                client);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** A non-negative number of seconds, or a string of one; {@code fallback} otherwise. */
    private static Duration seconds(JsonNode node, String field, Duration fallback) {
        if (node == null || !node.isObject()) return fallback;
        JsonNode v = node.get(field);
        double n;
        if (v != null && v.isNumber()) {
            n = v.asDouble();
        } else if (v != null && v.isTextual()) {
            try {
                n = Double.parseDouble(v.asText().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        } else {
            return fallback;
        }
        return Double.isFinite(n) && n >= 0 ? Duration.ofMillis(Math.round(n * 1000)) : fallback;
    }

    /** How a {@link ConsoleAuth} talks to the BFF. */
    public static final class Builder {
        private final String endpoint;
        private String clientId = SDK_CLIENT_ID;
        private HttpClient http;
        private String userAgent = ConsoleClient.DEFAULT_USER_AGENT;
        private Duration timeout = Duration.ofSeconds(30);
        private Clock clock = Clock.systemUTC();
        private Sleeper sleeper = Sleeper.system();

        private Builder(String endpoint) {
            this.endpoint = endpoint;
        }

        /** {@code console-sdk} (the default) or {@code console-cli}: the clients the BFF accepts. */
        public Builder clientId(String clientId) {
            this.clientId = Objects.requireNonNull(clientId, "clientId");
            return this;
        }

        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        /** Sent as {@code User-Agent}; the approval page shows it. */
        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /** How long one request may take (30 seconds). */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
            return this;
        }

        public ConsoleAuth build() {
            return new ConsoleAuth(this);
        }
    }
}
