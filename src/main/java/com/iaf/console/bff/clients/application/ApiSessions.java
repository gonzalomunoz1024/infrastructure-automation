package com.iaf.console.bff.clients.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.clients.domain.ApiSession;
import com.iaf.console.bff.clients.domain.InvalidBearerTokenException;
import com.iaf.console.bff.clients.domain.NotMemberException;
import com.iaf.console.bff.clients.ports.outbound.ApiSessionStore;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.ApiAccessToken;
import com.iaf.console.bff.trust.domain.ApiTokenCheck;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * API sessions, the CLI and SDK sign-ins of decision 31: a bearer call's session, built from its
 * access token and the API session it names (read again at most once a {@code session-check},
 * from a cache this instance drops an entry of when it revokes it); a person's open ones, newest
 * first; and revoking one, which ends its refresh token at once and its access tokens at the next
 * check.
 */
public class ApiSessions {

    private static final Logger log = LoggerFactory.getLogger(ApiSessions.class);
    /** How many times a revoke is tried again when a refresh changed the session meanwhile. */
    static final int REVOKE_TRIES = 3;
    private static final long CACHE_SIZE = 100_000;

    /** A session as this instance last read it, and when. */
    private record Checked(ApiSession session, Instant at) {
    }

    private final ApiSessionStore store;
    private final Trust trust;
    private final Sessions sessions;
    private final ClientsProperties properties;
    private final Clock clock;
    private final Cache<String, Checked> checked;

    public ApiSessions(ApiSessionStore store, Trust trust, Sessions sessions, ClientsProperties properties, Clock clock) {
        this.store = store;
        this.trust = trust;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        // the entries' age is read from the console's clock below; the cache's own expiry only frees memory
        this.checked = Caffeine.newBuilder().maximumSize(CACHE_SIZE).expireAfterWrite(properties.sessionCheck().multipliedBy(2)).build();
    }

    /**
     * The session of a bearer call: the token must be one of the console API's access tokens
     * ({@link Trust#verifyApiAccessToken}) whose API session is open and agrees with it on the
     * person and the client, else {@link InvalidBearerTokenException} (401). The call is in the
     * account {@code X-Console-Account} names (one of the person's memberships, else
     * {@link NotMemberException}, 403) and the region {@code X-Console-Region} names (an unknown
     * one is a 400), else in the API session's own; its sign-in time is the token's
     * {@code auth_time}, the browser sign-in that approved the grant, which the step-up reads.
     */
    public Mono<Session> authenticate(String bearerToken, String accountHeader, String regionHeader) {
        return Mono.defer(() -> {
            ApiTokenCheck check = trust.verifyApiAccessToken(bearerToken);
            if (!check.isValid()) {
                return Mono.error(new InvalidBearerTokenException(check.refusal()));
            }
            ApiAccessToken token = check.token();
            Instant now = clock.instant();
            return current(token.sessionId(), now)
                    .switchIfEmpty(Mono.error(() -> new InvalidBearerTokenException("its API session is unknown")))
                    .flatMap(api -> {
                        if (!api.subject().equals(token.subject()) || !api.clientId().equals(token.clientId())) {
                            return Mono.error(new InvalidBearerTokenException("it does not match its API session"));
                        }
                        String ended = api.endedAt(now);
                        if (ended != null) {
                            return Mono.error(new InvalidBearerTokenException("its API session ended: " + ended));
                        }
                        return callSession(api, token, accountHeader, regionHeader, now);
                    });
        });
    }

    /** A person's open API sessions, newest first, a page at a time ({@code limit} at most 200, {@code after} the cursor of the page before). */
    public Mono<Page<ApiSession>> list(Session session, Integer limit, String after) {
        return Mono.defer(() -> {
            int size = CursorPaging.pageSize(limit);
            Instant afterCreatedAt = null;
            String afterId = null;
            if (after != null && !after.isBlank()) {
                Cursor cursor = CursorPaging.decode(after);
                if (!(cursor.sortValue() instanceof Long createdNanos) || !ApiSession.isId(cursor.id())) {
                    return Mono.error(new IllegalArgumentException("the cursor is not one this service issued"));
                }
                afterCreatedAt = Instant.ofEpochSecond(Math.floorDiv(createdNanos, 1_000_000_000L), Math.floorMod(createdNanos, 1_000_000_000L));
                afterId = cursor.id();
            }
            return store.listOpen(session.user().userId(), clock.instant(), size + 1, afterCreatedAt, afterId)
                    .map(found -> Page.from(found, size, s -> new Cursor(nanos(s.createdAt()), s.id())));
        });
    }

    /** Revokes one of the person's API sessions; another person's, or one no longer kept, is a 404. */
    public Mono<Void> revoke(Session session, String apiSessionId) {
        return Mono.defer(() -> {
            if (!ApiSession.isId(apiSessionId)) {
                return Mono.error(notFound());
            }
            return store.find(apiSessionId)
                    .filter(api -> api.user().userId().equals(session.user().userId()))
                    .switchIfEmpty(Mono.error(ApiSessions::notFound))
                    .flatMap(api -> revoke(api, clock.instant(), "revoked by the person"))
                    .then();
        });
    }

    /**
     * Revokes an API session for a reason the log keeps: its refresh token stops working at once,
     * and its access tokens here at once and on another instance at its next check. A change made
     * meanwhile (a refresh) makes it read the session again and try once more.
     */
    Mono<ApiSession> revoke(ApiSession session, Instant now, String because) {
        return revokeTry(session, now, because, 1);
    }

    /** Keeps a session this instance just changed, so its next bearer call sees the change. */
    void remember(ApiSession session, Instant now) {
        checked.put(session.id(), new Checked(session, now));
    }

    private Mono<ApiSession> revokeTry(ApiSession session, Instant now, String because, int attempt) {
        if (session.revokedAt() != null) {
            checked.invalidate(session.id());
            return Mono.just(session);
        }
        return store.update(session.revoked(now, because), session.version())
                .doOnNext(revoked -> {
                    checked.invalidate(revoked.id());
                    log.info("api session revoked: id={} user={} client={} because {}", revoked.id(), revoked.user().userId(), revoked.clientId(), because);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (attempt >= REVOKE_TRIES) {
                        return Mono.error(new IllegalStateException("the API session " + session.id() + " kept changing; it was not revoked"));
                    }
                    return store.find(session.id()).flatMap(fresh -> revokeTry(fresh, now, because, attempt + 1));
                }));
    }

    /**
     * The API session of an id: as this instance read it less than a {@code session-check} ago and
     * still open then, else as the store holds it now. A cached copy that has ended is read again,
     * since a refresh elsewhere may have moved its idle end.
     */
    private Mono<ApiSession> current(String id, Instant now) {
        Checked last = checked.getIfPresent(id);
        if (last != null && !now.isBefore(last.at()) && now.isBefore(last.at().plus(properties.sessionCheck())) && last.session().openAt(now)) {
            return Mono.just(last.session());
        }
        return store.find(id).doOnNext(found -> remember(found, now));
    }

    /** The console session of one bearer call, in the account and region it names or the API session's own. */
    private Mono<Session> callSession(ApiSession api, ApiAccessToken token, String accountHeader, String regionHeader, Instant now) {
        Session own = api.callSession(api.accountId(), api.region(), token.authTime(), now);
        boolean namesAccount = accountHeader != null && !accountHeader.isBlank();
        boolean namesRegion = regionHeader != null && !regionHeader.isBlank();
        if (!namesAccount && !namesRegion) {
            return Mono.just(own);
        }
        if (namesAccount && api.membership(accountHeader.strip()).isEmpty()) {
            return Mono.error(new NotMemberException(accountHeader.strip()));
        }
        return sessions.switched(own, namesAccount ? accountHeader.strip() : null, namesRegion ? regionHeader.strip() : null);
    }

    private static long nanos(Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }

    private static NotFoundException notFound() {
        return new NotFoundException("no such API session");
    }
}
