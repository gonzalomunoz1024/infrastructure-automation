package com.iaf.console.search.load;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.forAll;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

/**
 * The state service at the scale targets (WP 6.2): lists, resources, their logs and creations,
 * against a running state service whose MongoDB holds the estate (the state service's
 * {@code ./gradlew seedEstate} with the same {@code load.size}, {@code load.accounts},
 * {@code load.seed} and {@code load.largestShare}, which lays the accounts out as {@link Estate}
 * does). Each virtual user is a person at the console behind the BFF: they open a list (newest
 * changes first, by name, or newest created; sometimes the next page), filter it (by tag, state or
 * name prefix), open a resource from it, read its log, or read the account's trail, thinking 5 to
 * 15 seconds between actions. One in ten works in the largest account, the others in an account
 * chosen by the square root of its size. Beside them, creations arrive at {@code load.creationsPerHour}
 * (10,000 an hour, the target), each a mint as the BFF sends it.
 *
 * <p>The state service accepts only the BFF's tokens (hub decision 35), so the simulation plays the
 * BFF: it serves a JWKS on {@code load.jwksPort} (18999) and signs each person's token with its key;
 * start the service with {@code --console.auth.jwks-uri=http://<this host>:18999/.well-known/jwks.json}.
 * The assertions are the plan's: a list's p95 under 300 ms ({@code load.listP95}), a resource, its
 * log and a mint under 300 ms too ({@code load.readP95}, {@code load.mintP95}), the trail under 1 s,
 * every creation of the run accepted, and under 1% failed requests.
 *
 * <pre>
 *   ./gradlew gatlingRun --simulation com.iaf.console.search.load.StateSimulation \
 *       -Pload.users=1000 -Pload.duration=PT10M -Pload.stateUrl=http://localhost:18082
 * </pre>
 */
public final class StateSimulation extends Simulation {

    private final Estate estate = Estate.fromProperties();
    private final String baseUrl = System.getProperty("load.stateUrl", "http://localhost:18082");
    private final int users = Integer.getInteger("load.users", 100);
    private final Duration ramp = Duration.parse(System.getProperty("load.ramp", "PT1M"));
    private final Duration duration = Duration.parse(System.getProperty("load.duration", "PT5M"));
    private final double hotShare = Double.parseDouble(System.getProperty("load.hotShare", "0.1"));
    private final Duration thinkMin = Duration.parse(System.getProperty("load.thinkMin", "PT5S"));
    private final Duration thinkMax = Duration.parse(System.getProperty("load.thinkMax", "PT15S"));
    private final double creationsPerHour = Double.parseDouble(System.getProperty("load.creationsPerHour", "10000"));
    private final long listP95 = Long.getLong("load.listP95", 300);
    private final long readP95 = Long.getLong("load.readP95", 300);
    private final long mintP95 = Long.getLong("load.mintP95", 300);
    private final long trailP95 = Long.getLong("load.trailP95", 1_000);
    private final double maxFailedPercent = Double.parseDouble(System.getProperty("load.maxFailedPercent", "1.0"));
    private final int jwksPort = Integer.getInteger("load.jwksPort", 18999);

    private final double[] weights = cumulativeWeights(estate);
    private final ECKey key = generateKey();
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private HttpServer jwks;

    private final HttpProtocolBuilder protocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            // the BFF calls the state service over a pool of its own, not a connection per person
            .shareConnections()
            // every read is a whole read: the target is the answer, not a 304
            .disableCaching()
            .disableWarmUp();

    private final Iterator<Map<String, Object>> people = endless(this::person);
    private final Iterator<Map<String, Object>> creations = endless(this::creation);

    private ChainBuilder asPerson(HttpRequestActionBuilder request) {
        return exec(request.header("Authorization", session -> "Bearer " + token(session.getString("account"), session.getString("user"))));
    }

    /** A list as the console opens it ({@code query} in the session), and sometimes its next page; the first row is kept to open. */
    private ChainBuilder list(String name, String query) {
        return exec(session -> session.set("query", session.getString("type") == null ? query : query.replace("#{type}", session.getString("type"))
                        .replace("#{env}", session.getString("env")).replace("#{prefix}", session.getString("prefix"))))
                .exec(asPerson(http(name).get("/v1/resources?#{query}")
                        .check(status().is(200), jsonPath("$.items[0].wfrn").optional().saveAs("wfrn"), jsonPath("$.next").optional().saveAs("next"))))
                .doIf(session -> session.contains("next") && !"null".equals(session.getString("next")) && ThreadLocalRandom.current().nextDouble() < 0.3)
                .then(asPerson(http("list next page").get("/v1/resources?#{query}&after=#{next}").check(status().is(200))));
    }

    private final ChainBuilder openAList = randomSwitch().on(
            percent(50.0).then(list("list", "type=#{type}&limit=50")),
            percent(25.0).then(list("list by name", "type=#{type}&sort=name&limit=50")),
            percent(25.0).then(list("list newest created", "sort=-createdAt&limit=50")));

    private final ChainBuilder filterAList = randomSwitch().on(
            percent(40.0).then(list("list by tag", "type=#{type}&tag.env=#{env}&limit=50")),
            percent(30.0).then(list("list by state", "state=active&type=#{type}&limit=50")),
            percent(30.0).then(list("list by name prefix", "name=#{prefix}&sort=name&limit=50")));

    private final ChainBuilder openAResource = exec(list("list", "type=#{type}&limit=50"))
            .doIf(session -> session.contains("wfrn"))
            .then(asPerson(http("resource").get(session -> "/v1/wfrn/" + encode(session.getString("wfrn"))).check(status().is(200))));

    private final ChainBuilder readItsLog = exec(list("list", "type=#{type}&limit=50"))
            .doIf(session -> session.contains("wfrn"))
            .then(asPerson(http("resource events").get(session -> "/v1/wfrn/" + encode(session.getString("wfrn")) + "/events?limit=50")
                    .check(status().is(200))));

    private final ChainBuilder readTheTrail = asPerson(http("activity").get("/v1/activity?limit=50").check(status().is(200)));

    private final ScenarioBuilder atTheConsole = scenario("people at the console").during(duration).on(
            feed(people),
            randomSwitch().on(
                    percent(40.0).then(openAList),
                    percent(25.0).then(filterAList),
                    percent(20.0).then(openAResource),
                    percent(10.0).then(readItsLog),
                    percent(5.0).then(readTheTrail)),
            exec(session -> session.remove("next").remove("wfrn")).pause(thinkMin, thinkMax));

    /** A creation as the BFF records it: the mint, with the request's idempotency key, before the platform job starts. */
    private final ScenarioBuilder creating = scenario("creations").exec(feed(creations),
            asPerson(http("mint").post("/v1/resources:mint").header("Idempotency-Key", "#{key}")
                    .body(StringBody("#{body}"))
                    .check(status().is(201), jsonPath("$.state").is("pending"))));

    {
        List<Assertion> assertions = new ArrayList<>();
        for (String list : List.of("list", "list next page", "list by name", "list newest created", "list by tag", "list by state",
                "list by name prefix")) {
            assertions.add(details(list).responseTime().percentile(95.0).lt((int) listP95));
        }
        assertions.add(details("resource").responseTime().percentile(95.0).lt((int) readP95));
        assertions.add(details("resource events").responseTime().percentile(95.0).lt((int) readP95));
        assertions.add(details("activity").responseTime().percentile(95.0).lt((int) trailP95));
        assertions.add(details("mint").responseTime().percentile(95.0).lt((int) mintP95));
        long expected = (long) Math.floor(creationsPerHour / 3600.0 * duration.toSeconds());
        assertions.add(details("mint").successfulRequests().count().gte(Math.max(0, (long) (expected * 0.98))));
        assertions.add(global().failedRequests().percent().lt(maxFailedPercent));
        assertions.add(forAll().failedRequests().percent().lt(Math.max(maxFailedPercent, 1.0)));
        setUp(
                atTheConsole.injectClosed(rampConcurrentUsers(0).to(users).during(ramp), constantConcurrentUsers(users).during(duration)),
                creating.injectOpen(nothingFor(ramp), constantUsersPerSec(creationsPerHour / 3600.0).during(duration)))
                .protocols(protocol)
                .maxDuration(ramp.plus(duration).plus(thinkMax).plusSeconds(30))
                .assertions(assertions);
    }

    // the BFF's part: its keys and its tokens

    @Override
    public void before() {
        serveJwks();
    }

    @Override
    public void after() {
        if (jwks != null) {
            jwks.stop(0);
        }
    }

    private void serveJwks() {
        try {
            jwks = HttpServer.create(new InetSocketAddress(jwksPort), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot serve the JWKS on port " + jwksPort, e);
        }
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks.createContext("/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        jwks.start();
    }

    /** A person's token as the BFF signs it (hub decision 35): one audience, the account, viewer and operator roles; good for the whole run. */
    private String token(String account, String user) {
        return tokens.computeIfAbsent(account + "/" + user, k -> {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("console-bff")
                    .audience("console-state-service")
                    .subject(user)
                    .claim("svc", "console-bff")
                    .claim("acct", account)
                    .claim("region", Estate.REGION)
                    .claim("groups", List.of("developers"))
                    .claim("roles", List.of("viewer", "operator"))
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ramp).plus(duration).plus(Duration.ofMinutes(10))))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
            try {
                jwt.sign(new ECDSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        });
    }

    private static ECKey generateKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("load-" + UUID.randomUUID()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // who acts, on what

    private Map<String, Object> person() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int account = random.nextDouble() < hotShare ? 0 : weighted(random.nextDouble() * weights[weights.length - 1]);
        Estate.Kind kind = pick(random.nextDouble());
        Map<String, Object> record = new HashMap<>();
        record.put("account", Estate.accountId(account));
        // a few hundred people per account at most, so tokens are reused as a BFF's sessions are
        record.put("user", "person-" + random.nextInt(300));
        record.put("type", kind.type());
        record.put("env", Estate.ENVS.get(random.nextInt(Estate.ENVS.size())));
        record.put("prefix", Estate.APPS.get(random.nextInt(Estate.APPS.size())) + "-");
        return record;
    }

    private Map<String, Object> creation() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int account = weighted(random.nextDouble() * weights[weights.length - 1]);
        String name = Estate.APPS.get(random.nextInt(Estate.APPS.size())) + "-load-" + Long.toString(random.nextLong() & Long.MAX_VALUE, 36);
        Map<String, Object> record = new HashMap<>();
        record.put("account", Estate.accountId(account));
        record.put("user", "person-" + random.nextInt(300));
        record.put("key", "load-" + UUID.randomUUID());
        record.put("body", "{\"type\":\"vm\",\"region\":\"" + Estate.REGION + "\",\"name\":\"" + name + "\",\"workflow\":\"create-vm\","
                + "\"input\":{\"name\":\"" + name + "\",\"image\":\"img-base-ubuntu\",\"size\":\"small\",\"environment\":\"dev\"}}");
        return record;
    }

    private static Estate.Kind pick(double u) {
        double sum = 0;
        for (Estate.Kind k : Estate.KINDS) {
            sum += k.share();
            if (u < sum) {
                return k;
            }
        }
        return Estate.KINDS.get(Estate.KINDS.size() - 1);
    }

    private static String encode(String wfrn) {
        return URLEncoder.encode(wfrn, StandardCharsets.UTF_8);
    }

    private static <T> Iterator<T> endless(java.util.function.Supplier<T> next) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public T next() {
                return next.get();
            }
        };
    }

    private int weighted(double u) {
        int lo = 0;
        int hi = weights.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (weights[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double[] cumulativeWeights(Estate estate) {
        double[] w = new double[estate.accounts()];
        double sum = 0;
        for (int k = 0; k < w.length; k++) {
            sum += Math.sqrt(estate.accountSize(k));
            w[k] = sum;
        }
        return w;
    }
}
