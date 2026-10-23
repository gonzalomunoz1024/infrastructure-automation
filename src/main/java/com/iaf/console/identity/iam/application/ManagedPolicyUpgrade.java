package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.ManagedIam;
import com.iaf.console.identity.iam.domain.Policy;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Brings the managed policies of every account that is not closed to what {@link ManagedIam} says
 * now, so a release that changes a managed role (the access reviews' {@code notActions}, decision
 * 25's cost and budget actions) reaches the accounts made before it, as a cloud's managed policies
 * change for every account at once. A policy that already says it is left alone; one that
 * differs is replaced in place (its id, the next version) with a {@code PolicyChanged}
 * {@code updated} that recompiles the account. Replicas that start together race on the version:
 * the one that loses reads the policy again and finds it current. One account that fails is
 * logged and left to the next start.
 */
public final class ManagedPolicyUpgrade {

    private static final Logger log = LoggerFactory.getLogger(ManagedPolicyUpgrade.class);

    private final AccountStore accounts;
    private final PolicyStore policies;
    private final IdentityEvents events;
    private final IamSupport support;
    private final Clock clock;

    public ManagedPolicyUpgrade(AccountStore accounts, PolicyStore policies, IdentityEvents events, IamSupport support, Clock clock) {
        this.accounts = accounts;
        this.policies = policies;
        this.events = events;
        this.support = support;
        this.clock = clock;
    }

    /** Replaces every managed policy that is out of date; answers how many it replaced. */
    public Mono<Long> run() {
        String actor = Caller.internal("console-identity-service", "global").actor();
        return accounts.findAll()
                .filter(account -> !account.isClosed())
                .concatMap(account -> upgrade(account, actor)
                        .onErrorResume(e -> {
                            log.warn("the managed policies of account {} were not brought up to date: {}", account.accountId(), e.toString());
                            return Flux.empty();
                        }))
                .count();
    }

    private Flux<Policy> upgrade(Account account, String actor) {
        AccountScope scope = AccountScope.of(account.accountId(), account.defaultRegion() == null ? "global" : account.defaultRegion(), actor);
        return Flux.fromIterable(ManagedIam.roles(support.partition(), account.accountId()))
                .concatMap(managed -> replace(scope, managed, actor, true));
    }

    private Mono<Policy> replace(AccountScope scope, ManagedIam.ManagedRole managed, String actor, boolean again) {
        return policies.findByName(scope, managed.policyName())
                .filter(current -> current.builtIn() && !says(current, managed))
                .flatMap(current -> policies.update(current.replaced(managed.policyName(), managed.policyDescription(), managed.statements(), clock.instant()),
                                current.version())
                        .flatMap(stored -> events.changed(IdentityEvents.Subject.POLICY, stored.policyId(), scope.accountId(), actor, stored.version(), "updated",
                                stored).thenReturn(stored))
                        // another replica replaced it first: read it again, once
                        .switchIfEmpty(Mono.defer(() -> again ? replace(scope, managed, actor, false) : Mono.empty())));
    }

    /** Whether a stored managed policy says what the managed role's policy says now. */
    static boolean says(Policy policy, ManagedIam.ManagedRole managed) {
        return policy.statements().equals(managed.statements()) && Objects.equals(policy.description(), managed.policyDescription());
    }
}
