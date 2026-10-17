package com.iaf.platform.providers.adapters.outbound;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.iaf.platform.common.adapters.http.OutboundAddresses;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.providers.ports.outbound.HttpProbePort;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The wizard's network adapter. Fetches an OpenAPI document (redirects followed, 10 s) and probes
 * an endpoint (HEAD, then GET when HEAD is refused, 5 s each) with the provider's auth resolved
 * through the {@link CredentialsPort}. Link-local and cloud metadata addresses are refused
 * (see {@link OutboundAddresses}). In the demo profile there is no network: hosts under
 * {@code example.com} are simulated as reachable with a small latency; anything else is tried for real.
 */
@Slf4j
@Component
public class WebClientHttpProbe implements HttpProbePort {

    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_DOCUMENT_BYTES = 32 * 1024 * 1024;
    static final String SIMULATED_SUFFIX = "example.com";

    private final WebClient client;
    private final CredentialsPort credentials;
    private final boolean simulateExampleHosts;

    @Autowired
    public WebClientHttpProbe(WebClient.Builder builder, CredentialsPort credentials, Environment environment) {
        this(builder, credentials, environment.matchesProfiles("demo"));
    }

    WebClientHttpProbe(WebClient.Builder builder, CredentialsPort credentials, boolean simulateExampleHosts) {
        HttpClient http = OutboundAddresses.guard(HttpClient.create()
                .responseTimeout(FETCH_TIMEOUT)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) PROBE_TIMEOUT.toMillis()));
        this.client = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_DOCUMENT_BYTES))
                .build();
        this.credentials = credentials;
        this.simulateExampleHosts = simulateExampleHosts;
    }

    @Override
    public Mono<String> fetch(String url) {
        return Mono.defer(() -> {
            URI uri = uri(url);
            return client.get().uri(uri).retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, r -> Mono.error(new IllegalArgumentException(
                            "HTTP " + r.statusCode().value() + " from " + url + ": the redirect points at a host the platform does not call")))
                    .bodyToMono(String.class)
                    .timeout(FETCH_TIMEOUT)
                    .onErrorMap(WebClientResponseException.class, e -> new IllegalArgumentException("HTTP " + e.getStatusCode().value() + " from " + url))
                    .onErrorMap(TimeoutException.class, e -> new IllegalArgumentException("No answer from " + url + " within " + FETCH_TIMEOUT.toSeconds() + " s"));
        });
    }

    @Override
    public Mono<ProbeResult> probe(String url, Auth auth) {
        return Mono.defer(() -> {
            URI uri = uri(url);
            String host = uri.getHost();
            if (simulateExampleHosts && isSimulated(host)) {
                long latency = 20 + Math.floorMod(host.hashCode(), 60);
                return Mono.delay(Duration.ofMillis(latency))
                        .map(t -> new ProbeResult(true, latency, "Simulated in the demo profile: " + host + " answered in " + latency + " ms (no network)"));
            }
            return headers(auth).flatMap(h -> attempt(HttpMethod.HEAD, uri, h)
                    .flatMap(head -> head.retryWithGet() ? attempt(HttpMethod.GET, uri, h) : Mono.just(head))
                    .map(a -> new ProbeResult(a.ok(), a.durationMs(), a.message())));
        }).onErrorResume(e -> Mono.just(new ProbeResult(false, 0, reason(e))));
    }

    static boolean isSimulated(String host) {
        return host != null && (host.equalsIgnoreCase(SIMULATED_SUFFIX) || host.toLowerCase().endsWith("." + SIMULATED_SUFFIX));
    }

    private record Attempt(boolean ok, long durationMs, String message, boolean retryWithGet) {
    }

    private Mono<Attempt> attempt(HttpMethod method, URI uri, HttpHeaders headers) {
        long started = System.nanoTime();
        return client.method(method).uri(uri).headers(h -> h.addAll(headers))
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                .timeout(PROBE_TIMEOUT)
                .map(status -> judge(method, status, elapsed(started)))
                .onErrorResume(e -> {
                    long ms = elapsed(started);
                    boolean timeout = e instanceof TimeoutException;
                    String message = timeout ? method + " timed out after " + PROBE_TIMEOUT.toSeconds() + " s" : method + " failed: " + reason(e);
                    return Mono.just(new Attempt(false, ms, message, method == HttpMethod.HEAD && !timeout));
                });
    }

    static Attempt judge(HttpMethod method, int status, long ms) {
        String prefix = method + " " + status + " in " + ms + " ms";
        if (status >= 200 && status < 400) {
            return new Attempt(true, ms, prefix, false);
        }
        if (status == 401 || status == 403) {
            return new Attempt(false, ms, prefix + ": reachable, but the credential was rejected", false);
        }
        if (status == 404 || status == 405 || status == 501) {
            if (method == HttpMethod.HEAD) {
                return new Attempt(false, ms, prefix, true);
            }
            return new Attempt(true, ms, prefix + ": host reachable, nothing served at the base path", false);
        }
        if (status >= 500) {
            return new Attempt(false, ms, prefix + ": server error", method == HttpMethod.HEAD);
        }
        return new Attempt(false, ms, prefix, method == HttpMethod.HEAD);
    }

    private Mono<HttpHeaders> headers(Auth auth) {
        HttpHeaders headers = new HttpHeaders();
        if (auth == null || auth.type() == null || "none".equals(auth.type()) || auth.credential() == null || auth.credential().isBlank()) {
            return Mono.just(headers);
        }
        return credentials.resolve(auth.credential())
                .map(secret -> {
                    switch (auth.type()) {
                        case "bearer" -> headers.setBearerAuth(secret);
                        case "basic" -> headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
                        case "header" -> headers.set(auth.header() == null || auth.header().isBlank() ? "X-Api-Key" : auth.header(), secret);
                        default -> {
                        }
                    }
                    return headers;
                })
                .defaultIfEmpty(headers);
    }

    static URI uri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an http(s) URL: " + url);
        }
        return OutboundAddresses.requireAllowed(uri);
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
