package com.iaf.console.bff.drift.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.drift.domain.DriftCaller;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;

import reactor.core.publisher.Mono;

/**
 * The policy questions every drift page asks, asked the same way everywhere. A row of a list is
 * shown when the person may list its type ({@code <service>:list} on the type's pattern), and what
 * they may do with it is decided in the same batch, so a page costs one {@code access.decide}; a
 * row whose wfrn is outside the session's account and region is left out, as every route is scoped
 * to them. One resource's pages need the wfrn to name the path's type in the session's account and
 * region (anything else is a 404, never a hint), as the other resource sub-routes do.
 */
public class DriftPolicies {

    static final String LIST = "list";
    static final String GET = "get";
    static final String UPDATE = "update";
    static final String DELETE = "delete";
    static final String CREATE = "create";
    static final String OBSERVE = "observe";

    private final Types types;
    private final Access access;
    private final Sessions sessions;
    private final PlatformProperties platform;

    public DriftPolicies(Types types, Access access, Sessions sessions, PlatformProperties platform) {
        this.types = types;
        this.access = access;
        this.sessions = sessions;
        this.platform = platform;
    }

    /** The answers to one batch of questions, by question. */
    public record Answers(Map<AccessCheck, Decision> decisions) {

        public boolean allowed(AccessCheck check) {
            Decision decision = check == null ? null : decisions.get(check);
            return decision != null && decision.allowed();
        }
    }

    /** Asks every distinct question once, in one batch. */
    public Mono<Answers> ask(Session session, Collection<AccessCheck> checks) {
        List<AccessCheck> distinct = List.copyOf(new LinkedHashSet<>(checks));
        if (distinct.isEmpty()) {
            return Mono.just(new Answers(Map.of()));
        }
        return access.decide(session, distinct).map(decisions -> {
            Map<AccessCheck, Decision> byCheck = new HashMap<>();
            for (int i = 0; i < distinct.size(); i++) {
                byCheck.put(distinct.get(i), decisions.get(i));
            }
            return new Answers(byCheck);
        });
    }

    /** {@code <service>:list} on a type's pattern in the session's account and region. */
    public AccessCheck list(Session session, String service, String type) {
        return new AccessCheck(AccessCheck.action(service, LIST), pattern(session, service, type));
    }

    /** {@code <service>:<verb>} on a type's pattern in the session's account and region. */
    public AccessCheck onType(Session session, ConsoleType type, String verb) {
        return new AccessCheck(AccessCheck.action(type.service(), verb), pattern(session, type.service(), type.type()));
    }

    /** {@code <service>:<verb>} on one resource. */
    public static AccessCheck onResource(Wfrn wfrn, String verb, String name) {
        return new AccessCheck(AccessCheck.action(wfrn.service(), verb), name);
    }

    /** The wfrn of a row when it names the session's account and region; null otherwise, so the row is left out. */
    public static Wfrn inScope(Session session, String wfrn) {
        Wfrn parsed = parse(wfrn);
        return parsed != null && parsed.in(session.accountId(), session.region()) ? parsed : null;
    }

    /** The wfrn of a finding, an alarm or a recommendation the drift service answered; a 404 when it is not the session's, as a stranger's would be. */
    public static Wfrn ownedBy(Session session, String what, String id, String wfrn) {
        Wfrn parsed = inScope(session, wfrn);
        if (parsed == null) {
            throw NotFoundException.of(what, id);
        }
        return parsed;
    }

    /**
     * A type a list's filter names: 400 {@code type.unknown} when the session's region has no such
     * type, 403 with the denial when the person may not list it (a list of one type is refused as
     * the type's own list is).
     */
    public Mono<ConsoleType> listableType(Session session, String type) {
        return types.byType(session, type)
                .onErrorMap(NotFoundException.class, e -> DriftInputs.invalid("type.unknown", "type", "no type '" + type + "' in this region"))
                .flatMap(t -> access.require(session, AccessCheck.action(t.service(), LIST), pattern(session, t.service(), t.type())).thenReturn(t));
    }

    /** A type a write names (discovery, import, assignment): 404 when the session's region has no such type. */
    public Mono<ConsoleType> type(Session session, String type) {
        return types.byType(session, type);
    }

    /** Every type of the session's region by name, for the rows that carry a type but no wfrn. */
    public Mono<Map<String, ConsoleType>> typesByName(Session session) {
        return types.list(session).map(all -> {
            Map<String, ConsoleType> byName = new HashMap<>();
            all.forEach(t -> byName.put(t.type(), t));
            return byName;
        });
    }

    /** The type of one resource's page, with the wfrn checked: 404 unless it names the path's type in the session's account and region. */
    public Mono<ConsoleType> resource(Session session, String type, String wfrn) {
        return types.byType(session, type).map(t -> {
            Wfrn parsed = parse(wfrn);
            if (parsed == null || !parsed.type().equals(t.type()) || !parsed.in(session.accountId(), session.region())) {
                throw NotFoundException.of("resource", wfrn);
            }
            return t;
        });
    }

    /** Completes when the person may do {@code <service>:<verb>} on the resource or pattern; a 403 with the denial otherwise. */
    public Mono<Void> require(Session session, String service, String verb, String resource) {
        return access.require(session, AccessCheck.action(service, verb), resource);
    }

    /**
     * As {@link #require}, then the fresh sign-in the step-up policy asks for the action: for the
     * writes that run a workflow (a revert, an apply), which are guarded like the verbs they run.
     */
    public Mono<Void> requireFresh(Session session, String service, String verb, String resource) {
        String action = AccessCheck.action(service, verb);
        return access.require(session, action, resource).then(sessions.requireFreshFor(session, action));
    }

    public String pattern(Session session, String service, String type) {
        return AccessCheck.typePattern(platform.partition(), service, session.region(), session.accountId(), type);
    }

    public static AccountScope scope(Session session) {
        return CurrentSession.scope(session);
    }

    /** The person behind the session, as the drift service hears them. */
    public static DriftCaller caller(Session session) {
        return DriftCaller.person(session.user().userId(), session.currentAccount().groups(), session.currentAccount().roles());
    }

    static Wfrn parse(String wfrn) {
        if (wfrn == null || wfrn.isBlank()) {
            return null;
        }
        try {
            return Wfrn.parse(wfrn);
        } catch (IllegalArgumentException e) {
            // a WfrnException, or a part the record itself refuses
            return null;
        }
    }
}
