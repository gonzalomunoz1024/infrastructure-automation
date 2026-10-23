package com.iaf.console.bff.clients.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.BrowserOnlyException;
import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.domain.OAuthException;
import com.iaf.console.bff.clients.domain.RequestedFrom;
import com.iaf.console.bff.clients.domain.Secrets;
import com.iaf.console.bff.clients.domain.UserCode;
import com.iaf.console.bff.clients.ports.outbound.DeviceGrantStore;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpRequiredException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * The device grant's first half (RFC 8628): a CLI or SDK starts a sign-in and gets its codes, at
 * most so many a minute from one address; then a person signed in to the console in a browser
 * reads the grant its user code names and approves it, with a sign-in as fresh as a delete needs,
 * or denies it. A grant is decided once and never after it expired, and its code is unknown ten
 * minutes past its expiry. Neither code is ever logged.
 */
public class DeviceAuthorization {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthorization.class);

    /** The clients that sign in with the device grant. */
    public static final List<String> CLIENTS = List.of("console-cli", "console-sdk");
    /** The action a step-up refusal of an approval names. */
    public static final String APPROVE_ACTION = "device-grants:approve";
    /** How many user codes are drawn before giving up, when the ones drawn are taken. */
    static final int CODE_DRAWS = 5;
    /** How many times a decision is tried again when a poll changed the grant meanwhile. */
    static final int DECISION_TRIES = 3;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final DeviceGrantStore store;
    private final Sessions sessions;
    private final ClientsProperties properties;
    private final Clock clock;
    private final RandomGenerator random;

    public DeviceAuthorization(DeviceGrantStore store, Sessions sessions, ClientsProperties properties, Clock clock) {
        this(store, sessions, properties, clock, new SecureRandom());
    }

    DeviceAuthorization(DeviceGrantStore store, Sessions sessions, ClientsProperties properties, Clock clock, RandomGenerator random) {
        this.store = store;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    /**
     * A started sign-in, as RFC 8628 §3.2 answers it: the device code (the client's secret, never
     * stored), the user code as {@code XXXX-XXXX}, the page to approve it on, the same page with the
     * code filled in, and the seconds the codes live and between polls.
     */
    public record Started(String deviceCode, String userCode, String verificationUri, String verificationUriComplete, long expiresIn, long interval) {
    }

    /**
     * Starts a sign-in for a client: an unknown client is {@code invalid_client} (400), and an
     * address that started {@code grants-per-minute} in the last minute is {@code slow_down} (429).
     */
    public Mono<Started> start(String clientId, RequestedFrom from) {
        return Mono.defer(() -> {
            if (clientId == null || !CLIENTS.contains(clientId)) {
                return Mono.error(OAuthException.unknownClient());
            }
            Instant now = clock.instant();
            return store.countRequestedFrom(from.ip(), now.minus(RATE_WINDOW)).flatMap(count -> {
                if (count >= properties.grantsPerMinute()) {
                    log.info("device sign-in refused: {} started from {} within a minute", count, from.ip());
                    return Mono.error(OAuthException.tooManyGrants());
                }
                String deviceCode = Secrets.generate();
                return create(clientId, Secrets.hash(deviceCode), from, now, 1).map(grant -> {
                    log.info("device sign-in started: client={} from={}", clientId, from.ip());
                    String userCode = UserCode.display(grant.userCode());
                    String page = properties.verificationUri();
                    return new Started(deviceCode, userCode, page, page + (page.contains("?") ? "&" : "?") + "code=" + userCode,
                            properties.deviceCodeTtl().toSeconds(), grant.interval());
                });
            });
        });
    }

    /** The grant a typed user code names; a 404 when the code is malformed, unknown, or ten minutes past its expiry. */
    public Mono<DeviceGrant> find(String typedCode) {
        return Mono.defer(() -> {
            Optional<String> code = UserCode.canonical(typedCode);
            if (code.isEmpty()) {
                return Mono.error(notFound());
            }
            return store.findByUserCode(code.get(), clock.instant()).switchIfEmpty(Mono.error(DeviceAuthorization::notFound));
        });
    }

    /** What the page shows as the grant's status now: {@code pending}, {@code approved}, {@code denied} or {@code expired}. */
    public String statusOf(DeviceGrant grant) {
        return grant.statusAt(clock.instant());
    }

    /**
     * Approves a grant as the person of a browser session, in its account and region: decided
     * already is a 409, expired a 410, and a sign-in older than the step-up policy's
     * {@code max-age} the 403 {@code step-up.required} a delete gets, whatever its action list.
     */
    public Mono<DeviceGrant> approve(Session session, String typedCode) {
        return decide(session, typedCode, (grant, now) -> {
            if (!sessions.stepUpPolicy().fresh(session, now)) {
                throw new StepUpRequiredException(APPROVE_ACTION);
            }
            return grant.approve(DeviceGrant.Approver.of(session), now);
        }).doOnNext(grant -> log.info("device sign-in approved: client={} user={} account={} region={}", grant.clientId(), session.user().userId(),
                session.accountId(), session.region()));
    }

    /** Denies a grant: decided already is a 409, expired a 410. */
    public Mono<DeviceGrant> deny(Session session, String typedCode) {
        return decide(session, typedCode, (grant, now) -> grant.deny(now))
                .doOnNext(grant -> log.info("device sign-in denied: client={} user={}", grant.clientId(), session.user().userId()));
    }

    private Mono<DeviceGrant> decide(Session session, String typedCode, BiFunction<DeviceGrant, Instant, DeviceGrant> decision) {
        return Mono.defer(() -> {
            if (ApiSession.isId(session.id())) {
                // the bearer filter refuses these calls already; an API session never approves another
                return Mono.error(new BrowserOnlyException());
            }
            return find(typedCode).flatMap(grant -> decided(grant, decision, 1));
        });
    }

    /** One try at a decision; a poll that changed the grant meanwhile makes it read the grant again and try once more. */
    private Mono<DeviceGrant> decided(DeviceGrant grant, BiFunction<DeviceGrant, Instant, DeviceGrant> decision, int attempt) {
        Instant now = clock.instant();
        grant.requireUndecided(now);
        DeviceGrant next = decision.apply(grant, now);
        return store.update(next, grant.version()).switchIfEmpty(Mono.defer(() -> {
            if (attempt >= DECISION_TRIES) {
                return Mono.error(new IllegalStateException("the device grant kept changing; it was not decided"));
            }
            return store.findByUserCode(grant.userCode(), now).switchIfEmpty(Mono.error(DeviceAuthorization::notFound))
                    .flatMap(fresh -> decided(fresh, decision, attempt + 1));
        }));
    }

    private Mono<DeviceGrant> create(String clientId, String deviceCodeHash, RequestedFrom from, Instant now, int draw) {
        DeviceGrant grant = DeviceGrant.requested(UserCode.generate(random), deviceCodeHash, clientId, from, now, properties.deviceCodeTtl(),
                properties.interval());
        return store.create(grant).flatMap(created -> {
            if (created) {
                return Mono.just(grant);
            }
            if (draw >= CODE_DRAWS) {
                return Mono.error(new IllegalStateException("no free user code after " + CODE_DRAWS + " draws"));
            }
            return create(clientId, deviceCodeHash, from, now, draw + 1);
        });
    }

    private static NotFoundException notFound() {
        return new NotFoundException("no device sign-in waits for this code");
    }
}
