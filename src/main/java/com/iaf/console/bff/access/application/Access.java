package com.iaf.console.bff.access.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.RoleConventions;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/**
 * The console's own authorization: a session's user may do an action on a resource when the
 * account's policies say so. Decisions are cached per account, user, action and resource for a
 * short while and dropped when the account's identity changes; a refusal carries the
 * {@link Denial} the identity service explained it with, completed with the user's role. An
 * allow that only a role held for a while gave ({@code elevated}) is not cached.
 */
public class Access {

    private record Key(String accountId, String userId, String action, String resource) {
    }

    private final AuthorizationPort authorization;
    private final Cache<Key, Decision> cache;

    public Access(AuthorizationPort authorization, AccessProperties properties) {
        this.authorization = authorization;
        this.cache = Caffeine.newBuilder().expireAfterWrite(properties.cacheTtl()).maximumSize(properties.cacheSize()).build();
    }

    /** One decision per check, in order. */
    public Mono<List<Decision>> decide(Session session, List<AccessCheck> checks) {
        List<Decision> answers = new ArrayList<>(checks.size());
        List<AccessCheck> missing = new ArrayList<>();
        List<Integer> missingAt = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) {
            AccessCheck check = checks.get(i);
            Decision cached = cache.getIfPresent(key(session, check));
            answers.add(cached);
            if (cached == null) {
                missing.add(check);
                missingAt.add(i);
            }
        }
        if (missing.isEmpty()) {
            return Mono.just(List.copyOf(answers));
        }
        return authorization.decide(CurrentSession.scope(session), session.user(), session.currentAccount().groups(), missing).map(decisions -> {
            for (int j = 0; j < missing.size(); j++) {
                Decision decision = j < decisions.items().size() ? decisions.items().get(j) : Decision.deny(Denial.byPolicy(missing.get(j).action(),
                        missing.get(j).resource(), "no decision was returned", null, null, null));
                decision = withRole(decision, session);
                if (!decision.elevated()) {
                    // an allow a time-boxed role gave ends at the role's until, which the cache does not know
                    cache.put(key(session, missing.get(j)), decision);
                }
                answers.set(missingAt.get(j), decision);
            }
            return List.copyOf(answers);
        });
    }

    /** The decisions keyed by action, for descriptor greying. */
    public Mono<Map<String, Decision>> decideByAction(Session session, List<AccessCheck> checks) {
        return decide(session, checks).map(decisions -> {
            Map<String, Decision> byAction = new LinkedHashMap<>();
            for (int i = 0; i < checks.size(); i++) {
                byAction.put(checks.get(i).action(), decisions.get(i));
            }
            return byAction;
        });
    }

    /** Completes when allowed; errors with {@link DeniedException} otherwise. */
    public Mono<Void> require(Session session, String action, String resource) {
        return decide(session, List.of(new AccessCheck(action, resource))).flatMap(decisions -> {
            Decision decision = decisions.get(0);
            return decision.allowed() ? Mono.empty() : Mono.error(new DeniedException(decision.denial()));
        });
    }

    /** Drops every cached decision of an account, after an identity change. */
    public void invalidate(String accountId) {
        cache.asMap().keySet().removeIf(key -> key.accountId().equals(accountId));
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    private static Key key(Session session, AccessCheck check) {
        return new Key(session.accountId(), session.user().userId(), check.action(), check.resource());
    }

    private static Decision withRole(Decision decision, Session session) {
        if (decision.allowed() || decision.denial() == null || decision.denial().role() != null) {
            return decision;
        }
        return Decision.deny(decision.denial().withRole(RoleConventions.strongest(session.currentAccount().roles())));
    }
}
