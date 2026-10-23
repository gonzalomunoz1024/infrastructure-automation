package com.iaf.console.bff.clients.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.DeviceGrant;
import com.iaf.console.bff.clients.domain.OAuthException;
import com.iaf.console.bff.clients.domain.RequestedFrom;
import com.iaf.console.bff.clients.domain.Secrets;
import com.iaf.console.bff.clients.ports.outbound.ApiSessionStore;
import com.iaf.console.bff.clients.ports.outbound.DeviceGrantStore;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.ApiAccessToken;

import reactor.core.publisher.Mono;

/**
 * The token endpoint of decision 31: a device code redeemed for an API session and its first
 * tokens (RFC 8628 §3.4), and a refresh token exchanged for the next ones (RFC 6749 §6), each
 * refresh token used once. A refresh token presented after it was rotated away revokes the whole
 * session, as OAuth 2.1's reuse detection has it; so does a device code presented after it was
 * redeemed. A refresh asks the identity service for the person's memberships again, as a browser
 * sign-in does. Then the revoke endpoint (RFC 7009). Only the SHA-256 of a device code or a
 * refresh token is looked up or kept, and none is logged.
 */
public class ApiTokens {

    private static final Logger log = LoggerFactory.getLogger(ApiTokens.class);

    /** RFC 8628's grant type. */
    public static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    /** RFC 6749's refresh grant type. */
    public static final String REFRESH_GRANT = "refresh_token";
    /** The one scope an access token carries. */
    public static final String SCOPE = "console";
    /** How many times a poll is tried again when another poll or a decision changed the grant meanwhile. */
    static final int POLL_TRIES = 2;

    private final DeviceGrantStore grants;
    private final ApiSessionStore store;
    private final ApiSessions apiSessions;
    private final PrincipalResolverPort resolver;
    private final Trust trust;
    private final ClientsProperties properties;
    private final Clock clock;

    public ApiTokens(DeviceGrantStore grants, ApiSessionStore store, ApiSessions apiSessions, PrincipalResolverPort resolver, Trust trust,
            ClientsProperties properties, Clock clock) {
        this.grants = grants;
        this.store = store;
        this.apiSessions = apiSessions;
        this.resolver = resolver;
        this.trust = trust;
        this.properties = properties;
        this.clock = clock;
    }

    /** A token request's fields (RFC 6749 §4.1.3, RFC 8628 §3.4), each sent at most once. */
    public record TokenRequest(String grantType, String clientId, String deviceCode, String refreshToken) {
    }

    /**
     * A token answer: the access token and the seconds it lives, the refresh token, the seconds
     * until the API session ends whatever the refreshes, and the session's id (for the log).
     */
    public record Issued(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn, String sessionId) {
    }

    /**
     * Answers a token request, or refuses it with an {@link OAuthException}: a missing field is
     * {@code invalid_request}, an unknown client {@code invalid_client} (401), another grant type
     * {@code unsupported_grant_type}.
     */
    public Mono<Issued> exchange(TokenRequest request, RequestedFrom from) {
        return Mono.defer(() -> {
            if (blank(request.grantType())) {
                return Mono.error(OAuthException.invalidRequest("grant_type is required"));
            }
            if (blank(request.clientId())) {
                return Mono.error(OAuthException.invalidRequest("client_id is required"));
            }
            if (!DeviceAuthorization.CLIENTS.contains(request.clientId())) {
                return Mono.error(OAuthException.invalidClient("unknown client_id: the console's clients are console-cli and console-sdk"));
            }
            return switch (request.grantType()) {
                case DEVICE_CODE_GRANT -> redeem(request.clientId(), request.deviceCode(), from);
                case REFRESH_GRANT -> refresh(request.clientId(), request.refreshToken());
                default -> Mono.error(OAuthException.unsupportedGrantType("grant_type is " + DEVICE_CODE_GRANT + " or " + REFRESH_GRANT));
            };
        });
    }

    /**
     * Revokes the API session a refresh token belongs to, current or rotated away (RFC 7009): the
     * answer is the same whatever the token was, so nothing can be learnt from it.
     */
    public Mono<Void> revoke(String token) {
        return Mono.defer(() -> {
            if (blank(token)) {
                return Mono.empty();
            }
            String hash = Secrets.hash(token);
            Instant now = clock.instant();
            return store.findByRefreshToken(hash)
                    .filter(api -> Secrets.sameHash(api.refreshHash(), hash))
                    .switchIfEmpty(Mono.defer(() -> store.findByRotatedToken(hash).filter(api -> api.rotatedAway(hash))))
                    .flatMap(api -> apiSessions.revoke(api, now, "signed out"))
                    .then();
        });
    }

    // ---- the device grant

    private Mono<Issued> redeem(String clientId, String deviceCode, RequestedFrom from) {
        if (blank(deviceCode)) {
            return Mono.error(OAuthException.invalidRequest("device_code is required"));
        }
        String hash = Secrets.hash(deviceCode);
        return grants.findByDeviceCode(hash, clock.instant())
                .filter(grant -> Secrets.sameHash(grant.deviceCodeHash(), hash))
                .switchIfEmpty(Mono.error(() -> OAuthException.invalidGrant("the device code is unknown or expired; start again")))
                .flatMap(grant -> {
                    if (!grant.clientId().equals(clientId)) {
                        return Mono.error(OAuthException.invalidClient("the device code was issued to another client"));
                    }
                    return poll(grant, from, 1);
                });
    }

    /** One poll of a grant (RFC 8628 §3.5); a grant changed meanwhile is read again and polled once more, then answered {@code slow_down}. */
    private Mono<Issued> poll(DeviceGrant grant, RequestedFrom from, int attempt) {
        Instant now = clock.instant();
        DeviceGrant.Polled polled = grant.poll(now, ApiSession.newId());
        return switch (polled.outcome()) {
            case ALREADY_REDEEMED -> redeemedAgain(grant, now);
            case ACCESS_DENIED -> Mono.error(OAuthException.accessDenied());
            case EXPIRED_TOKEN -> Mono.error(OAuthException.expiredToken());
            case AUTHORIZATION_PENDING, SLOW_DOWN, REDEEMED -> grants.update(polled.next(), grant.version())
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(kept -> {
                        if (kept.isEmpty()) {
                            if (attempt >= POLL_TRIES) {
                                return Mono.error(OAuthException.slowDown(grant.interval()));
                            }
                            return grants.findByDeviceCode(grant.deviceCodeHash(), now)
                                    .switchIfEmpty(Mono.error(() -> OAuthException.invalidGrant("the device code is unknown or expired; start again")))
                                    .flatMap(fresh -> poll(fresh, from, attempt + 1));
                        }
                        DeviceGrant next = kept.get();
                        return switch (polled.outcome()) {
                            case SLOW_DOWN -> Mono.error(OAuthException.slowDown(next.interval()));
                            case REDEEMED -> open(next, from, now);
                            default -> Mono.error(OAuthException.authorizationPending());
                        };
                    });
        };
    }

    /** A device code presented after its redemption: refused, and the API session it opened is revoked, as RFC 6749 §4.1.2 asks of a code used twice. */
    private Mono<Issued> redeemedAgain(DeviceGrant grant, Instant now) {
        Mono<Void> revoked = grant.apiSessionId() == null ? Mono.empty()
                : store.find(grant.apiSessionId()).flatMap(api -> {
                    log.warn("a redeemed device code was presented again: api session {} of {} revoked", api.id(), api.user().userId());
                    return apiSessions.revoke(api, now, "its device code was presented again");
                }).then();
        return revoked.then(Mono.error(OAuthException.invalidGrant("the device code was redeemed already")));
    }

    /** The API session an approved grant opens, and its first tokens. */
    private Mono<Issued> open(DeviceGrant redeemed, RequestedFrom from, Instant now) {
        String refreshToken = Secrets.generate();
        ApiSession api = ApiSession.open(redeemed, Secrets.hash(refreshToken), from, now, properties.sessionTtl(), properties.idleTimeout());
        return store.create(api).then(Mono.fromSupplier(() -> {
            log.info("api session opened: id={} client={} user={} account={} region={}", api.id(), api.clientId(), api.user().userId(), api.accountId(),
                    api.region());
            apiSessions.remember(api, now);
            return issue(api, refreshToken, now);
        }));
    }

    // ---- the refresh

    private Mono<Issued> refresh(String clientId, String refreshToken) {
        if (blank(refreshToken)) {
            return Mono.error(OAuthException.invalidRequest("refresh_token is required"));
        }
        String hash = Secrets.hash(refreshToken);
        Instant now = clock.instant();
        return store.findByRefreshToken(hash)
                .filter(api -> Secrets.sameHash(api.refreshHash(), hash))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(current -> current.isPresent() ? rotate(current.get(), clientId, now) : replayed(hash, now));
    }

    /** A refresh with the session's current refresh token: the memberships read again, a new refresh token, a new access token. */
    private Mono<Issued> rotate(ApiSession api, String clientId, Instant now) {
        if (!api.clientId().equals(clientId)) {
            return Mono.error(OAuthException.invalidClient("the refresh token was issued to another client"));
        }
        String ended = api.endedAt(now);
        if (ended != null) {
            log.debug("refresh refused: api session {} {}", api.id(), ended);
            return Mono.error(ended());
        }
        Principal person = api.user();
        IdpUser user = new IdpUser(api.issuer(), api.subject(), blankToNull(person.email()), person.name(), person.groups(), api.authTime());
        return resolver.resolve(user)
                .onErrorResume(NoConsoleAccountException.class, e -> apiSessions.revoke(api, now, "the person belongs to no console account any more")
                        .then(Mono.error(OAuthException.invalidGrant("you belong to no console account any more"))))
                .flatMap(resolved -> {
                    String nextToken = Secrets.generate();
                    ApiSession next = api.rotated(Secrets.hash(nextToken), resolved.user(), resolved.accounts(), now, properties.idleTimeout());
                    // nothing at the version read: another refresh with the same token, or a revoke, came first
                    return store.update(next, api.version())
                            .switchIfEmpty(Mono.error(() -> OAuthException.invalidGrant("the refresh token was used by another request at the same time")))
                            .map(saved -> {
                                apiSessions.remember(saved, now);
                                return issue(saved, nextToken, now);
                            });
                });
    }

    /** A refresh token that is no session's current one: a rotated one presented again revokes its session; either way it is {@code invalid_grant}. */
    private Mono<Issued> replayed(String hash, Instant now) {
        return store.findByRotatedToken(hash)
                .filter(api -> api.rotatedAway(hash))
                .flatMap(api -> {
                    if (api.revokedAt() == null) {
                        log.warn("a rotated refresh token was presented again: api session {} of {} (client {}) revoked", api.id(), api.user().userId(),
                                api.clientId());
                    }
                    return apiSessions.revoke(api, now, "a rotated refresh token was presented again");
                })
                .then(Mono.error(ApiTokens::ended));
    }

    /** The tokens of an open session: the access token lives its time, or until the session ends when that comes first. */
    private Issued issue(ApiSession api, String refreshToken, Instant now) {
        Instant end = api.purgeAt();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        if (end.isBefore(expiresAt)) {
            expiresAt = end;
        }
        String accessToken = trust.signApiAccessToken(new ApiAccessToken(api.subject(), api.id(), api.clientId(), api.authTime(), now, expiresAt,
                UUID.randomUUID().toString()));
        return new Issued(accessToken, Duration.between(now, expiresAt).toSeconds(), refreshToken, Duration.between(now, api.expiresAt()).toSeconds(),
                api.id());
    }

    /** The one refusal of a refresh token that no longer works, whatever the reason. */
    private static OAuthException ended() {
        return OAuthException.invalidGrant("the refresh token is not valid any more; run console login again");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
