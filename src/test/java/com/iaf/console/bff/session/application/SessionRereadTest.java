package com.iaf.console.bff.session.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.CsrfTokens;
import com.iaf.console.bff.session.domain.IdpUser;
import com.iaf.console.bff.session.domain.NoConsoleAccountException;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.ResolvedPrincipal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.domain.StepUpPolicy;
import com.iaf.console.bff.session.ports.outbound.PrincipalResolverPort;
import com.iaf.console.bff.session.ports.outbound.SessionStore;

import reactor.core.publisher.Mono;

/** Review finding 1: a session's roles are read again when due, never used past a time-boxed role's end, and lost with the account. */
class SessionRereadTest {

    private static final String COMPUTE = "123456789012";
    private static final String DATA = "210987654321";
    private static final Instant T0 = Instant.parse("2026-10-23T08:00:00Z");
    private static final Principal DANA = new Principal("user_dana", "dana@example.com", "Dana", List.of());

    private final MutableClock clock = new MutableClock(T0);
    private final Map<String, Session> stored = new ConcurrentHashMap<>();
    private final AtomicReference<Object> answer = new AtomicReference<>();
    private final AtomicInteger resolves = new AtomicInteger();
    private final AtomicReference<IdpUser> asked = new AtomicReference<>();
    private final SessionProperties properties = new SessionProperties("console_session", "console_login", Duration.ofHours(12), Duration.ofHours(1),
            Duration.ofMinutes(10), true, "Lax", "/", null, Duration.ofMinutes(5));
    private final MembershipChanges changes = new MembershipChanges(Duration.ofMinutes(5), clock);
    private final Sessions sessions = new Sessions(store(), new CsrfTokens(new byte[32]), new StepUpPolicy(Duration.ofMinutes(10), List.of("*:delete")),
            () -> Mono.just(List.of("global")), resolver(), changes, properties, clock);

    @Test
    void theRolesAreReadAgainAfterTheIntervalAndNotBefore() {
        Session session = signedIn(membership(COMPUTE, "account-admin", null));
        answer.set(new ResolvedPrincipal(DANA, List.of(membership(COMPUTE, "viewer", null))));
        clock.set(T0.plus(Duration.ofMinutes(4)));
        assertEquals(List.of("account-admin"), sessions.find(session.id()).block().currentAccount().roles());
        assertEquals(0, resolves.get(), "not due yet");
        clock.set(T0.plus(Duration.ofMinutes(5)));
        Session reread = sessions.find(session.id()).block();
        assertEquals(List.of("viewer"), reread.currentAccount().roles());
        assertEquals(1, resolves.get());
        assertEquals(List.of("idp-compute-admins"), asked.get().groups(), "the re-read sends the sign-in's provider groups again");
        assertEquals(T0.plus(Duration.ofMinutes(10)), reread.rolesUntil());
        assertEquals(List.of("viewer"), stored.get(session.id()).currentAccount().roles(), "the re-read is stored");
    }

    @Test
    void anElevationEndsTheRolesAtItsUntil() {
        Instant until = T0.plus(Duration.ofSeconds(90));
        Session session = signedIn(membership(COMPUTE, "account-admin", until), membership(DATA, "viewer", null));
        assertEquals(until, session.rolesUntil(), "the earliest until of any account caps the roles");
        answer.set(new ResolvedPrincipal(DANA, List.of(membership(COMPUTE, "operator", null), membership(DATA, "viewer", null))));
        clock.set(until.minusSeconds(1));
        assertEquals(List.of("account-admin"), sessions.find(session.id()).block().currentAccount().roles());
        clock.set(until);
        assertEquals(List.of("operator"), sessions.find(session.id()).block().currentAccount().roles(), "the elevation ended: read again at once");
    }

    @Test
    void someoneWhoLostTheSessionsAccountLosesTheSession() {
        Session session = signedIn(membership(COMPUTE, "operator", null), membership(DATA, "viewer", null));
        answer.set(new ResolvedPrincipal(DANA, List.of(membership(DATA, "viewer", null))));
        clock.set(T0.plus(Duration.ofMinutes(6)));
        assertNull(sessions.find(session.id()).block());
        assertFalse(stored.containsKey(session.id()));
    }

    @Test
    void someoneInNoAccountLosesTheSession() {
        Session session = signedIn(membership(COMPUTE, "operator", null));
        answer.set(new NoConsoleAccountException("dana"));
        clock.set(T0.plus(Duration.ofMinutes(6)));
        assertNull(sessions.find(session.id()).block());
        assertFalse(stored.containsKey(session.id()));
    }

    @Test
    void anIdentityChangeMakesTheNextRequestReadAgain() {
        Session session = signedIn(membership(COMPUTE, "account-admin", null));
        answer.set(new ResolvedPrincipal(DANA, List.of(membership(COMPUTE, "viewer", null))));
        clock.set(T0.plusSeconds(10));
        changes.userChanged("user_someone_else");
        changes.accountChanged(DATA);
        assertEquals(List.of("account-admin"), sessions.find(session.id()).block().currentAccount().roles(), "others' changes are not hers");
        changes.userChanged("user_dana");
        assertEquals(List.of("viewer"), sessions.find(session.id()).block().currentAccount().roles());
        clock.set(T0.plusSeconds(20));
        assertEquals(1, resolves.get());
        sessions.find(session.id()).block();
        assertEquals(1, resolves.get(), "the change is read once");
        clock.set(T0.plusSeconds(30));
        changes.accountChanged(COMPUTE);
        sessions.find(session.id()).block();
        assertEquals(2, resolves.get(), "a change to her account");
    }

    @Test
    void aFailedRereadRefusesRatherThanKeepingTheRoles() {
        Session session = signedIn(membership(COMPUTE, "account-admin", null));
        answer.set(new IllegalStateException("identity is down"));
        clock.set(T0.plus(Duration.ofMinutes(5)));
        assertThrows(IllegalStateException.class, () -> sessions.find(session.id()).block());
        assertTrue(stored.containsKey(session.id()), "the session stays for the next try");
    }

    @Test
    void aSessionStoredBeforeTheRereadsIsEnded() {
        Session legacy = new Session("legacy", DANA, List.of(membership(COMPUTE, "account-admin", null)), COMPUTE, "global", T0, T0, T0,
                T0.plus(Duration.ofHours(12)), "https://idp.example", "dana");
        stored.put(legacy.id(), legacy);
        assertNull(sessions.find("legacy").block());
        assertEquals(0, resolves.get());
    }

    private Session signedIn(AccountMembership... accounts) {
        List<AccountMembership> list = List.of(accounts);
        Session session = new Session("s_" + resolves.get() + "_" + stored.size(), DANA, list, list.get(0).accountId(), "global", T0, T0, T0,
                T0.plus(Duration.ofHours(12)), "https://idp.example", "dana", List.of("idp-compute-admins"), T0,
                Sessions.rolesUntil(T0, list, properties.rolesRefreshInterval()));
        stored.put(session.id(), session);
        return session;
    }

    private static AccountMembership membership(String accountId, String role, Instant until) {
        return new AccountMembership(accountId, accountId, "active", "global", List.of("g"), List.of(role), until);
    }

    private PrincipalResolverPort resolver() {
        return user -> {
            resolves.incrementAndGet();
            asked.set(user);
            Object a = answer.get();
            return a instanceof RuntimeException e ? Mono.error(e) : Mono.just((ResolvedPrincipal) a);
        };
    }

    private SessionStore store() {
        return new SessionStore() {
            @Override
            public Mono<Session> find(String id) {
                return Mono.justOrEmpty(stored.get(id));
            }

            @Override
            public Mono<Void> save(Session session) {
                return Mono.fromRunnable(() -> stored.put(session.id(), session));
            }

            @Override
            public Mono<Void> delete(String id) {
                return Mono.fromRunnable(() -> stored.remove(id));
            }
        };
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
