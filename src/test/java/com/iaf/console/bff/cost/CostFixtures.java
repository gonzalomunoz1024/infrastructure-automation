package com.iaf.console.bff.cost;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Function;

import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.common.relay.RelayClient;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.jwk.ECKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/**
 * What the cost and scorecards unit tests share: a session of two accounts, the account's policies
 * as a predicate, a real {@link Trust} over a generated key, and a stub service on a free port that
 * records every request and answers what the test says.
 */
public final class CostFixtures {

    public static final String COMPUTE = "123456789012";
    public static final String DATA = "210987654321";
    public static final Instant NOW = Instant.parse("2026-10-23T14:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private CostFixtures() {
    }

    /** gonzalo in the compute account, a member of the data account too. */
    public static Session session() {
        Principal principal = new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo", List.of());
        AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("account-admin"));
        AccountMembership data = new AccountMembership(DATA, "Data", "active", "global", List.of("owners"), List.of("account-admin"));
        return new Session("s_gonzalo", principal, List.of(compute, data), COMPUTE, "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)),
                "https://idp.test", "gonzalo");
    }

    /** The policies: {@code allows(accountId, action)} per check, in the account the session is in. */
    public static Access access(BiPredicate<String, String> allows) {
        AuthorizationPort port = (scope, principal, groups, checks) -> Mono.just(new Decisions(checks.stream()
                .map(c -> allows.test(scope.accountId(), c.action()) ? Decision.allow()
                        : Decision.deny(Denial.byPolicy(c.action(), c.resource(), "No statement allows " + c.action(), "viewer", null, null)))
                .toList(), 1));
        return new Access(port, new AccessProperties(Duration.ofSeconds(60), 1000));
    }

    public static Sessions sessions() {
        return new Sessions(null, null, new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete")), null, null, null, null, CLOCK);
    }

    public static Trust trust() {
        ECKey signing = OnBehalfOfAssertions.generateKey("console-bff-test");
        TrustProperties properties = new TrustProperties("console-bff", "platform", Duration.ofSeconds(60), Duration.ofSeconds(30), null, null,
                Duration.ofMinutes(5), Duration.ofMinutes(5), new TrustProperties.Kubernetes(null, null, null, null, null, Duration.ofHours(1), true), Map.of(), null);
        SigningKeyPort keys = new SigningKeyPort() {
            @Override
            public ECKey currentKey() {
                return signing;
            }

            @Override
            public ECKey previousKey() {
                return null;
            }
        };
        return new Trust(keys, properties, Clock.systemUTC());
    }

    /** A relay client to a base URL, with the timeout of a person's calls. */
    public static RelayClient client(String baseUrl, String audience, Duration timeout) {
        return new RelayClient(WebClient.builder(), () -> baseUrl, trust(), audience, timeout);
    }

    /** A URL on which nothing listens: the service is down. */
    public static String nothingListening() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One request the stub saw. */
    public record Seen(String method, String path, String rawQuery, Map<String, String> headers, String body) {
    }

    /** What the stub answers. */
    public record Answer(int status, String contentType, String body, Duration delay) {

        public static Answer json(int status, String body) {
            return new Answer(status, "application/json", body, Duration.ZERO);
        }
    }

    /** A stub service on a free port: every request recorded, each answered by the handler. */
    public static final class Stub implements AutoCloseable {

        private final HttpServer server;
        public final List<Seen> seen = new CopyOnWriteArrayList<>();

        public Stub(Function<Seen, Answer> handler) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> handle(exchange, handler));
            server.start();
        }

        private void handle(HttpExchange exchange, Function<Seen, Answer> handler) throws IOException {
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen request = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(), headers, body);
            seen.add(request);
            Answer answer = handler.apply(request);
            if (!answer.delay().isZero()) {
                try {
                    Thread.sleep(answer.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
            if (answer.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", answer.contentType());
            }
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException e) {
                // the client gave up (a timeout test)
            }
        }

        public String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
