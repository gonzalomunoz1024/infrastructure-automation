package com.iaf.platform.providers.adapters.outbound;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
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
import com.iaf.platform.common.expressions.ExpressionContext;
import com.iaf.platform.common.expressions.ExpressionEngine;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.common.schema.Schemas.Mismatch;
import com.iaf.platform.providers.domain.OpenApiImporter;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.providers.domain.ProviderDefinition.ErrorClass;
import com.iaf.platform.providers.domain.ProviderDefinition.SpecSource;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The production {@link ProviderCallPort} for openapi and http providers: the endpoint of the
 * environment, the operation from the pinned spec (fetched once from its URL, or read from Git at
 * the pinned commit, and cached by its sha256) or the
 * action's method and path, {@code {param}} segments and query parameters filled from the input
 * and the rest sent as the JSON body, auth resolved through the {@link CredentialsPort} at call
 * time, errors classified by the provider's {@code errors} table, every call recorded on the
 * {@link TelemetryPort}, and the response compared with the action's schema (logged, never failed).
 * A dry run never touches the system: it answers from the action's sample.
 */
@Slf4j
@Component
@Profile("live")
@Primary
public class HttpProviderCall implements ProviderCallPort {

    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    static final Duration SPEC_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final Pattern PARAM = Pattern.compile("\\{([^/{}?&=]+)}");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final WebClient client;
    private final CredentialsPort credentials;
    private final TelemetryPort telemetry;
    private final Clock clock;
    private final GitSourcePort git;
    private final Map<String, Mono<JsonNode>> specs = new ConcurrentHashMap<>();

    public HttpProviderCall(WebClient.Builder builder, CredentialsPort credentials, TelemetryPort telemetry, Clock clock) {
        this(builder, credentials, telemetry, clock, null);
    }

    /** {@code git} reads specs pinned to a commit of a repository ({@code spec: { git, commit }}). */
    @Autowired
    public HttpProviderCall(WebClient.Builder builder, CredentialsPort credentials, TelemetryPort telemetry, Clock clock, GitSourcePort git) {
        HttpClient http = OutboundAddresses.guard(HttpClient.create()
                .responseTimeout(CALL_TIMEOUT)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000));
        this.client = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BODY_BYTES))
                .build();
        this.credentials = credentials;
        this.telemetry = telemetry;
        this.clock = clock;
        this.git = git;
    }

    /** The HTTP shape of an action: method, path template and the names of its query parameters. */
    record Operation(String method, String path, Set<String> queryParams) {
    }

    private record Raw(int status, String body) {
    }

    @Override
    public Mono<JsonNode> call(ProviderCall call) {
        return Mono.defer(() -> {
            ProviderDefinition p = call.provider();
            Action a = call.action();
            if (call.dryRun()) {
                return Mono.just(dryRunAnswer(call));
            }
            if (p.type() == ProviderType.SDK) {
                return Mono.error(new ProviderCallException("unsupported", sdkNeedsTemporal(p), false, 0));
            }
            if (p.type() != ProviderType.OPENAPI && p.type() != ProviderType.HTTP) {
                return Mono.error(new ProviderCallException("unsupported", "HttpProviderCall runs openapi and http providers; " + p.name() + " is " + p.type().key(), false, 0));
            }
            String base = endpoint(p, call.environment());
            return operation(p, a).flatMap(op -> headers(p.spec().auth()).flatMap(h -> execute(call, base, op, h)));
        });
    }

    /** Custom-code providers are served by their team's worker, which only the Temporal engine schedules work on. */
    static String sdkNeedsTemporal(ProviderDefinition p) {
        return "Provider " + p.name() + " is a custom-code provider (type sdk): its actions run on the team's own Temporal worker (task queue "
                + p.taskQueue() + "), so they only run on the Temporal engine. Start the backend with the temporal profile "
                + "(platform.execution.mode: temporal) and the worker on that task queue.";
    }

    // ---------------------------------------------------------------- where and what

    static String endpoint(ProviderDefinition p, String environment) {
        Map<String, String> endpoints = p.spec().endpoints() == null ? Map.of() : p.spec().endpoints();
        if (endpoints.isEmpty()) {
            throw new ProviderCallException("misconfigured", "Provider " + p.name() + " has no endpoints", false, 0);
        }
        String url = environment == null && endpoints.size() == 1 ? endpoints.values().iterator().next() : endpoints.get(environment);
        if (url == null || url.isBlank()) {
            throw new ProviderCallException("misconfigured", "Provider " + p.name() + " has no endpoint for environment '" + environment + "'; one of " + endpoints.keySet(), false, 0);
        }
        try {
            OutboundAddresses.requireAllowed(URI.create(url.trim()));
        } catch (IllegalArgumentException e) {
            throw new ProviderCallException("misconfigured", "Provider " + p.name() + ": " + e.getMessage(), false, 0);
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Mono<Operation> operation(ProviderDefinition p, Action a) {
        if (a.method() != null && a.path() != null) {
            return Mono.just(new Operation(a.method().toUpperCase(), a.path(), queryNames(a.path())));
        }
        if (a.operation() == null || a.operation().isBlank()) {
            return Mono.error(new ProviderCallException("misconfigured", "Action " + a.id() + " of " + p.name() + " names no operation", false, 0));
        }
        return spec(p).map(doc -> OpenApiImporter.find(doc, a.operation())
                .map(l -> new Operation(l.method(), l.path(), new LinkedHashSet<>(l.parameterNames("query"))))
                .orElseThrow(() -> new ProviderCallException("misconfigured", "Operation " + a.operation() + " is not in the spec of " + p.name(), false, 0)));
    }

    /** {@code {x}} names in the query part of a template like {@code /jobs?target={target}&state={state}}. */
    static Set<String> queryNames(String path) {
        Set<String> out = new LinkedHashSet<>();
        int q = path == null ? -1 : path.indexOf('?');
        if (q < 0) {
            return out;
        }
        Matcher m = PARAM.matcher(path.substring(q + 1));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private Mono<JsonNode> spec(ProviderDefinition p) {
        SpecSource s = p.spec().specSource();
        if (s == null || (!s.fromGit() && (s.url() == null || s.url().isBlank()) && (s.content() == null || s.content().isBlank()))) {
            return Mono.error(new ProviderCallException("misconfigured", "Provider " + p.name() + " has no spec to look operations up in", false, 0));
        }
        String key = s.sha256() != null && !s.sha256().isBlank() ? s.sha256().toLowerCase()
                : s.fromGit() ? "git:" + s.git().repo() + "@" + (s.commit() == null ? s.git().ref() : s.commit()) + ":" + s.git().path()
                : (s.url() != null ? s.url() : "content:" + WorkflowYaml.sha256(s.content()));
        // a loaded spec is kept for a day; a failed load is not cached, so the next call retries
        return specs.computeIfAbsent(key, k -> load(p, s).cache(doc -> Duration.ofHours(24), e -> Duration.ZERO, () -> Duration.ZERO));
    }

    private Mono<JsonNode> load(ProviderDefinition p, SpecSource s) {
        if (s.content() != null && !s.content().isBlank()) {
            return Mono.fromCallable(() -> OpenApiImporter.read(s.content()));
        }
        if (s.fromGit()) {
            if (git == null) {
                return Mono.error(new ProviderCallException("spec_unavailable", "Provider " + p.name() + " reads its spec from Git, and this platform has no Git reader", false, 0));
            }
            // the pinned commit, never the moving ref: the reviewed spec is the one that runs
            String at = s.commit() != null && !s.commit().isBlank() ? s.commit() : s.git().ref();
            return git.file(s.git().repo(), at, s.git().path(), s.git().credential())
                    .map(f -> {
                        if (s.sha256() != null && !s.sha256().isBlank() && !s.sha256().equalsIgnoreCase(WorkflowYaml.sha256(f.content()))) {
                            log.warn("the spec of provider {} at {} differs from the pinned sha256", p.name(), f.commit());
                        }
                        return OpenApiImporter.read(f.content());
                    })
                    .onErrorMap(e -> !(e instanceof ProviderCallException),
                            e -> new ProviderCallException("spec_unavailable", "Could not load the spec of " + p.name() + ": " + reason(e), true, 0));
        }
        return Mono.defer(() -> client.get().uri(OutboundAddresses.requireAllowed(URI.create(s.url().trim()))).retrieve().bodyToMono(String.class).timeout(SPEC_TIMEOUT))
                .map(raw -> {
                    if (s.sha256() != null && !s.sha256().isBlank() && !s.sha256().equalsIgnoreCase(WorkflowYaml.sha256(raw))) {
                        log.warn("the spec of provider {} at {} differs from the pinned sha256; operations may have drifted", p.name(), s.url());
                    }
                    return OpenApiImporter.read(raw);
                })
                .onErrorMap(e -> !(e instanceof ProviderCallException),
                        e -> new ProviderCallException("spec_unavailable", "Could not load the spec of " + p.name() + ": " + reason(e), true, 0));
    }

    // ---------------------------------------------------------------- auth

    private Mono<HttpHeaders> headers(Auth auth) {
        HttpHeaders headers = new HttpHeaders();
        if (auth == null || auth.type() == null || "none".equals(auth.type())) {
            return Mono.just(headers);
        }
        if (auth.credential() == null || auth.credential().isBlank()) {
            return Mono.error(new ProviderCallException("credential_missing", "Auth " + auth.type() + " needs a credential reference", false, 0));
        }
        return credentials.resolve(auth.credential())
                .switchIfEmpty(Mono.error(new ProviderCallException("credential_missing", "Credential " + auth.credential() + " is not in the vault", false, 0)))
                .map(secret -> {
                    switch (auth.type()) {
                        case "bearer" -> headers.setBearerAuth(secret);
                        case "basic" -> headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
                        case "header" -> headers.set(auth.header() == null || auth.header().isBlank() ? "X-Api-Key" : auth.header(), secret);
                        default -> throw new ProviderCallException("misconfigured", "Unknown auth type " + auth.type(), false, 0);
                    }
                    return headers;
                });
    }

    // ---------------------------------------------------------------- the call

    private Mono<JsonNode> execute(ProviderCall call, String base, Operation op, HttpHeaders headers) {
        ProviderDefinition p = call.provider();
        Action a = call.action();
        ObjectNode remaining = call.input() != null && call.input().isObject() ? ((ObjectNode) call.input()).deepCopy() : F.objectNode();
        String url = url(base, op, remaining);
        boolean withBody = BODY_METHODS.contains(op.method());
        long started = System.nanoTime();
        Instant at = clock.instant();
        WebClient.RequestBodySpec request = client.method(HttpMethod.valueOf(op.method())).uri(URI.create(url))
                .headers(h -> h.addAll(headers)).accept(MediaType.APPLICATION_JSON);
        WebClient.RequestHeadersSpec<?> ready = withBody ? request.contentType(MediaType.APPLICATION_JSON).bodyValue(remaining) : request;
        return ready.exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("").map(b -> new Raw(r.statusCode().value(), b)))
                .timeout(CALL_TIMEOUT)
                .flatMap(raw -> {
                    long ms = elapsed(started);
                    if (raw.status() >= 200 && raw.status() < 300) {
                        JsonNode json = parseBody(raw.body());
                        record(call, at, ms, true, null);
                        compare(p, a, json);
                        return Mono.just(json);
                    }
                    ProviderCallException ex = classify(p, raw.status(), raw.body());
                    record(call, at, ms, false, ex.errorClass());
                    return Mono.<JsonNode>error(ex);
                })
                .onErrorResume(e -> !(e instanceof ProviderCallException), e -> {
                    ProviderCallException ex = transport(e);
                    record(call, at, elapsed(started), false, ex.errorClass());
                    return Mono.error(ex);
                });
    }

    /** Fill {@code {param}} segments and the declared query parameters from the input, which loses those fields. */
    static String url(String base, Operation op, ObjectNode remaining) {
        String template = op.path() == null ? "" : op.path();
        String pathPart = template;
        String queryPart = null;
        int q = template.indexOf('?');
        if (q >= 0) {
            pathPart = template.substring(0, q);
            queryPart = template.substring(q + 1);
        }
        String path = fill(pathPart, remaining, true);
        List<String> pairs = new ArrayList<>();
        if (queryPart != null) {
            for (String pair : queryPart.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : fill(pair.substring(eq + 1), remaining, false);
                if (!value.isEmpty()) {
                    pairs.add(name + "=" + value);
                }
            }
        }
        for (String name : op.queryParams()) {
            JsonNode v = remaining.remove(name);
            if (v != null && !v.isNull()) {
                pairs.add(encode(name) + "=" + encode(text(v)));
            }
        }
        if (!BODY_METHODS.contains(op.method())) {
            Iterator<Map.Entry<String, JsonNode>> it = remaining.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isNull()) {
                    pairs.add(encode(e.getKey()) + "=" + encode(text(e.getValue())));
                }
                it.remove();
            }
        }
        String full = base + (path.startsWith("/") || path.isEmpty() ? path : "/" + path);
        return pairs.isEmpty() ? full : full + "?" + String.join("&", pairs);
    }

    private static String fill(String template, ObjectNode remaining, boolean pathSegment) {
        Matcher m = PARAM.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = remaining.get(m.group(1));
            String replacement = "";
            if (v != null && !v.isNull()) {
                replacement = encode(text(v));
                if (pathSegment) {
                    replacement = replacement.replace("%2F", "/");
                }
                remaining.remove(m.group(1));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String text(JsonNode v) {
        return v.isValueNode() ? v.asText() : v.toString();
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return F.objectNode();
        }
        try {
            JsonNode n = JSON.readTree(body);
            return n == null || n.isMissingNode() ? F.objectNode() : n;
        } catch (IOException e) {
            ObjectNode o = F.objectNode();
            o.put("body", body);
            return o;
        }
    }

    // ---------------------------------------------------------------- errors

    /** The provider's {@code errors} table first ({@code match.status}, optional {@code match.body} substring), then sensible defaults. */
    static ProviderCallException classify(ProviderDefinition p, int status, String body) {
        String message = "HTTP " + status + (body == null || body.isBlank() ? "" : ": " + (body.length() > 300 ? body.substring(0, 300) + "…" : body.trim()));
        if (p.spec().errors() != null) {
            for (ErrorClass e : p.spec().errors()) {
                JsonNode match = e.match();
                if (match == null || !match.isObject()) {
                    continue;
                }
                boolean statusMatches = !match.has("status") || match.get("status").asInt() == status;
                boolean bodyMatches = !match.has("body") || (body != null && body.contains(match.get("body").asText()));
                if (match.has("status") && statusMatches && bodyMatches) {
                    return new ProviderCallException(e.errorClass(), message, e.retry(), status);
                }
            }
        }
        return switch (status) {
            case 401, 403 -> new ProviderCallException("unauthorized", message, false, status);
            case 404 -> new ProviderCallException("not_found", message, false, status);
            case 409 -> new ProviderCallException("conflict", message, false, status);
            case 429 -> new ProviderCallException("rate_limited", message, true, status);
            case 408, 502, 503, 504 -> new ProviderCallException("unavailable", message, true, status);
            default -> status >= 500 ? new ProviderCallException("server_error", message, true, status) : new ProviderCallException("client_error", message, false, status);
        };
    }

    static ProviderCallException transport(Throwable e) {
        if (e instanceof TimeoutException) {
            return new ProviderCallException("timeout", "No answer within " + CALL_TIMEOUT.toSeconds() + " s", true, 0);
        }
        return new ProviderCallException("unreachable", reason(e), true, 0);
    }

    // ---------------------------------------------------------------- telemetry and schema

    private void record(ProviderCall call, Instant at, long ms, boolean ok, String errorClass) {
        try {
            telemetry.record(new CallSample(at, call.provider().name(), call.action().id(), call.environment(), ok, ms, errorClass, call.jobId(), null, null,
                    call.provider().metadata().version()));
        } catch (RuntimeException e) {
            log.warn("telemetry failed: {}", e.toString());
        }
    }

    private static void compare(ProviderDefinition p, Action a, JsonNode response) {
        JsonNode schema = a.output();
        JsonNode override = p.override(a.id());
        if (override != null && override.has("response")) {
            schema = Schemas.merge(schema, override.get("response"));
        }
        if (schema == null || schema.isNull()) {
            return;
        }
        List<Mismatch> mismatches = Schemas.mismatches(schema, response);
        if (!mismatches.isEmpty()) {
            log.info("response of {}/{} differs from its schema in {} field(s): {}", p.name(), a.id(), mismatches.size(),
                    mismatches.stream().limit(5).map(m -> m.field() + " expected " + m.expected() + " got " + m.actual()).toList());
        }
    }

    // ---------------------------------------------------------------- dry runs

    /** Previews and test runs never touch the system: the recorded sample, resolved against the input, or a sample of the schema. */
    static JsonNode dryRunAnswer(ProviderCall call) {
        Action a = call.action();
        JsonNode input = call.input() == null || call.input().isNull() ? F.objectNode() : call.input();
        if (a.sample() != null && !a.sample().isNull()) {
            try {
                return ExpressionEngine.resolve(a.sample(), new DryRunContext(input, call.jobId(), call.seq(), call.environment()));
            } catch (RuntimeException e) {
                return a.sample();
            }
        }
        return a.output() == null ? F.objectNode() : Schemas.sample(a.output());
    }

    private record DryRunContext(JsonNode input, String jobId, int seq, String environment) implements ExpressionContext {
        @Override
        public JsonNode json() {
            return F.objectNode();
        }

        @Override
        public JsonNode step(String nameOrId) {
            return null;
        }

        @Override
        public JsonNode alias(String name) {
            return null;
        }

        @Override
        public JsonNode vars() {
            return F.objectNode();
        }

        @Override
        public JsonNode job() {
            ObjectNode job = F.objectNode();
            job.put("id", jobId == null ? "dry-run" : jobId);
            job.put("seq", seq);
            return job;
        }

        @Override
        public JsonNode env() {
            ObjectNode env = F.objectNode();
            env.put("name", environment == null ? "dev" : environment);
            return env;
        }

        @Override
        public JsonNode error() {
            return F.nullNode();
        }
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String reason(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        String m = cause.getMessage();
        return m == null || m.isBlank() ? cause.getClass().getSimpleName() : m;
    }
}
