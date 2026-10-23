package com.iaf.console.bff.access.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.AccountAction;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/**
 * Decides every {@link AccountAction} for the person in a session's account, in one batch that
 * the decision cache answers afterwards. When the identity service cannot answer, the decisions
 * are left out (an empty result), so the session still loads and a refused call explains itself.
 */
public class AccountActions {

    private static final Logger log = LoggerFactory.getLogger(AccountActions.class);

    private final Access access;
    private final String partition;

    public AccountActions(Access access, String partition) {
        this.access = access;
        this.partition = partition;
    }

    /** The decisions keyed by {@link AccountAction#key()}, in {@link AccountAction#ALL}'s order; empty when they could not be decided. */
    public Mono<Map<String, Decision>> decide(Session session) {
        List<AccessCheck> checks = AccountAction.ALL.stream().map(a -> a.check(partition, session.accountId())).toList();
        return access.decide(session, checks).map(decisions -> {
            Map<String, Decision> byKey = new LinkedHashMap<>();
            for (int i = 0; i < checks.size(); i++) {
                byKey.put(AccountAction.ALL.get(i).key(), decisions.get(i));
            }
            return byKey;
        }).onErrorResume(e -> {
            log.warn("the account's console actions could not be decided for {} in {}: {}", session.user().userId(), session.accountId(), e.toString());
            return Mono.empty();
        });
    }
}
