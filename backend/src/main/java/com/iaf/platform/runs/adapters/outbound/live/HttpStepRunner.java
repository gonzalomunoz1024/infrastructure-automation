package com.iaf.platform.runs.adapters.outbound.live;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.adapters.http.OutboundAddresses;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

/**
 * Production {@code call: http}: one real request. The URL passes the outbound address guard
 * (no cloud metadata or link-local hosts, before the call and for every address DNS answers);
 * {@code credential: vault:<path>} becomes {@code Authorization: Bearer <secret>} and a header
 * whose value is {@code vault:<path>} (or {@code <scheme> vault:<path>}, like
 * {@code Basic vault:svc/basic}) gets the secret in its place. A request without secrets follows
 * redirects the guard allows; one with secrets follows none. The output is
 * {@code { status, body }} with the body parsed as JSON when it is JSON; a status outside 2xx
 * fails the step with the class its status implies.
 */
@Component
@Profile("live")
@Order(0)
public class HttpStepRunner implements AutomationRunnerPort {

    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final WebClient following;
    private final WebClient notFollowing;
    private final CredentialsPort credentials;
    private final ProviderQueries providers;
    private final RunnersProperties props;

    public HttpStepRunner(WebClient.Builder builder, CredentialsPort credentials, ProviderQueries providers, RunnersProperties props) {
        int maxBody = (int) Math.min(Integer.MAX_VALUE, props.getHttp().getMaxBody().toBytes());
        HttpClient base = OutboundAddresses.guard(HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000));
        this.following = builder.clone().clientConnector(new ReactorClientHttpConnector(base))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBody)).build();
        this.notFollowing = builder.clone().clientConnector(new ReactorClientHttpConnector(base.followRedirect(false)))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBody)).build();
        this.credentials = credentials;
        this.providers = providers;
        this.props = props;
    }

    @Override
    public boolean supports(StepType type) {
        return type == StepType.HTTP;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        return run(request, Progress.NONE);
    }

    private record Raw(int status, String body, String location) {
    }

    @Override
    public Mono<JsonNode> run(RunRequest request, Progress progress) {
        if (request.dryRun()) {
            return LiveSteps.recorded(providers, request);
        }
        JsonNode w = request.with() == null ? F.objectNode() : request.with();
        StepSecrets secrets = new StepSecrets(credentials);
        return Mono.fromCallable(() -> prepare(w, secrets))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(call -> send(call, request, progress));
    }

    /** What to send, with every secret already resolved (blocking: the vault lookups). */
    record Call(HttpMethod method, URI uri, HttpHeaders headers, JsonNode body, boolean secret, StepSecrets secrets) {
    }

    Call prepare(JsonNode w, StepSecrets secrets) {
        String url = LiveSteps.text(w, "url");
        if (url == null) {
            throw new IllegalArgumentException("An HTTP step needs a url");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url is not a URL: " + e.getMessage());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("url starts with https:// (or http://) and names a host");
        }
        try {
            OutboundAddresses.requireAllowed(uri);
        } catch (IllegalArgumentException e) {
            throw new ProviderCallException("refused", e.getMessage(), false, 0);
        }
        JsonNode body = w.get("body");
        boolean hasBody = body != null && !body.isNull() && !body.isMissingNode();
        String m = LiveSteps.text(w, "method");
        String method = m == null ? (hasBody ? "POST" : "GET") : m.toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("method is one of GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS");
        }
        HttpHeaders headers = new HttpHeaders();
        boolean secret = false;
        JsonNode h = w.get("headers");
        if (h != null && !h.isNull()) {
            if (!h.isObject()) {
                throw new IllegalArgumentException("headers is an object of names to values");
            }
            Iterator<Map.Entry<String, JsonNode>> it = h.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String value = e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString();
                String resolved = header(e.getKey(), value, secrets);
                secret |= !resolved.equals(value);
                headers.set(e.getKey(), resolved);
            }
        }
        String credential = LiveSteps.text(w, "credential");
        if (credential != null) {
            headers.setBearerAuth(secrets.resolve(credential, "credential"));
            secret = true;
        }
        return new Call(HttpMethod.valueOf(method), uri, headers, hasBody ? body : null, secret, secrets);
    }

    /** {@code vault:<path>} or {@code <scheme> vault:<path>} with the secret in place of the reference. */
    static String header(String name, String value, StepSecrets secrets) {
        if (StepSecrets.isReference(value.trim())) {
            return secrets.resolve(value.trim(), "header " + name);
        }
        String[] parts = value.trim().split(" ", 2);
        if (parts.length == 2 && StepSecrets.isReference(parts[1].trim()) && parts[0].matches("[A-Za-z][A-Za-z0-9_-]*")) {
            return parts[0] + " " + secrets.resolve(parts[1].trim(), "header " + name);
        }
        return value;
    }

    private Mono<JsonNode> send(Call call, RunRequest request, Progress progress) {
        WebClient client = call.secret() ? notFollowing : following;
        String target = call.method().name() + " " + call.uri().getScheme() + "://" + call.uri().getAuthority() + (call.uri().getRawPath() == null ? "" : call.uri().getRawPath());
        WebClient.RequestBodySpec spec = client.method(call.method()).uri(call.uri()).headers(x -> x.addAll(call.headers()));
        WebClient.RequestHeadersSpec<?> ready = spec;
        if (call.body() != null) {
            if (call.body().isTextual()) {
                ready = spec.contentType(call.headers().getContentType() == null ? MediaType.TEXT_PLAIN : call.headers().getContentType()).bodyValue(call.body().asText());
            } else {
                ready = spec.contentType(call.headers().getContentType() == null ? MediaType.APPLICATION_JSON : call.headers().getContentType()).bodyValue(call.body().toString());
            }
        }
        if (call.headers().getAccept().isEmpty()) {
            ready = ready.accept(MediaType.APPLICATION_JSON, MediaType.ALL);
        }
        String named = request.step() == null ? null : request.step().timeout();
        Duration timeout = named == null || named.isBlank() ? props.getHttp().getTimeout() : Durations.parse(named);
        return ready.exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new Raw(r.statusCode().value(), b, r.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION))))
                .timeout(timeout)
                .onErrorMap(e -> !(e instanceof ProviderCallException), e -> transport(e, timeout))
                .map(raw -> {
                    progress.report(target + " -> " + raw.status());
                    if (raw.status() >= 200 && raw.status() < 300) {
                        ObjectNode out = F.objectNode();
                        out.put("status", raw.status());
                        out.set("body", parse(raw.body()));
                        return (JsonNode) out;
                    }
                    if (raw.status() >= 300 && raw.status() < 400 && call.secret()) {
                        throw new ProviderCallException("redirect", "HTTP " + raw.status() + ": the request carries a credential, so its redirect to "
                                + host(raw.location()) + " is not followed", false, raw.status());
                    }
                    ProviderCallException e = classify(raw.status(), raw.body());
                    throw new ProviderCallException(e.errorClass(), call.secrets().mask(e.getMessage()), e.retryable(), raw.status());
                });
    }

    static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return F.objectNode();
        }
        try {
            JsonNode n = JSON.readTree(body);
            return n == null || n.isMissingNode() ? F.textNode(body) : n;
        } catch (IOException e) {
            return F.textNode(body);
        }
    }

    /** The class of a status outside 2xx: what a provider call would say about it. */
    static ProviderCallException classify(int status, String body) {
        String b = body == null ? "" : body.strip();
        String message = "HTTP " + status + (b.isEmpty() ? "" : ": " + (b.length() > 300 ? b.substring(0, 300) + "…" : b));
        return switch (status) {
            case 401, 403 -> new ProviderCallException("unauthorized", message, false, status);
            case 404 -> new ProviderCallException("not_found", message, false, status);
            case 409 -> new ProviderCallException("conflict", message, false, status);
            case 429 -> new ProviderCallException("rate_limited", message, true, status);
            case 408, 502, 503, 504 -> new ProviderCallException("unavailable", message, true, status);
            default -> status >= 500 ? new ProviderCallException("server_error", message, true, status)
                    : new ProviderCallException("client_error", message, false, status);
        };
    }

    static ProviderCallException transport(Throwable e, Duration timeout) {
        if (e instanceof TimeoutException) {
            return new ProviderCallException("timeout", "No answer within " + Durations.format(timeout), true, 0);
        }
        if (e instanceof DataBufferLimitException) {
            return new ProviderCallException("too_large", "The response is larger than platform.runners.http.max-body", false, 0);
        }
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String m = cause.getMessage();
        return new ProviderCallException("unreachable", m == null || m.isBlank() ? cause.getClass().getSimpleName() : m, true, 0);
    }

    private static String host(String location) {
        if (location == null) {
            return "another location";
        }
        try {
            URI u = URI.create(location.trim());
            return u.getHost() == null ? "a path on the same host" : u.getHost();
        } catch (IllegalArgumentException e) {
            return "another location";
        }
    }
}
