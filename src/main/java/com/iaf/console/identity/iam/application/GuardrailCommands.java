package com.iaf.console.identity.iam.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.domain.PolicyValidation;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The writes on the organization's guardrails: the internal principal's only (the BFF calls for
 * an organization admin; anyone else gets a 404). Every change is announced in each account it
 * touches, as a GuardrailChanged there ({@code deleted} where it no longer applies), which also
 * moves those accounts' authorization versions.
 */
public final class GuardrailCommands {

    private static final String NO_SUCH_OPERATION = "no such operation";

    private final GuardrailStore guardrails;
    private final AccountStore accounts;
    private final ActionCatalogPort catalog;
    private final IdentityEvents events;
    private final Ids ids;
    private final Clock clock;

    public GuardrailCommands(GuardrailStore guardrails, AccountStore accounts, ActionCatalogPort catalog, IdentityEvents events, Ids ids, Clock clock) {
        this.guardrails = guardrails;
        this.accounts = accounts;
        this.catalog = catalog;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Guardrail> create(Caller caller, GuardrailInput input) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException(NO_SUCH_OPERATION));
        }
        return catalog.getCatalog().flatMap(actions -> {
            Names.Check check = PolicyValidation.check(input.name(), input.statements(), actions);
            checkTargets(check, input.accounts(), true);
            check.done("the guardrail");
            return knownAccounts(input.accounts()).then(Mono.defer(() -> {
                Guardrail guardrail = Guardrail.create(ids.guardrail(), input.name(), input.description(), input.statements(), input.accounts(),
                        clock.instant());
                return guardrails.insert(guardrail).flatMap(stored -> announce(stored, null, caller.actor()).thenReturn(stored));
            }));
        });
    }

    public Mono<Guardrail> update(Caller caller, String guardrailId, GuardrailInput input, Long ifMatch) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException(NO_SUCH_OPERATION));
        }
        return guardrails.findById(guardrailId)
                .switchIfEmpty(Mono.error(NotFoundException.of("guardrail", guardrailId)))
                .flatMap(current -> catalog.getCatalog().flatMap(actions -> {
                    Versions.check(ifMatch, current.version(), "the guardrail");
                    Names.Check check = PolicyValidation.check(input.name() == null ? current.name() : input.name(),
                            input.statements() == null ? current.statements() : input.statements(), actions);
                    checkTargets(check, input.accounts(), false);
                    check.done("the guardrail");
                    Guardrail changed = current.changed(input.name(), input.description(), input.statements(), input.accounts(), clock.instant());
                    return knownAccounts(input.accounts())
                            .then(Mono.defer(() -> guardrails.update(changed, current.version())))
                            .switchIfEmpty(Mono.error(new ConflictException("the guardrail moved on; read it again")))
                            .flatMap(stored -> announce(stored, current, caller.actor()).thenReturn(stored));
                }));
    }

    public Mono<Void> delete(Caller caller, String guardrailId) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException(NO_SUCH_OPERATION));
        }
        return guardrails.findById(guardrailId)
                .switchIfEmpty(Mono.error(NotFoundException.of("guardrail", guardrailId)))
                .flatMap(current -> guardrails.delete(guardrailId)
                        .then(targets(current).concatMap(account -> events.changed(IdentityEvents.Subject.GUARDRAIL, guardrailId, account, caller.actor(),
                                current.version() + 1, "deleted", null)).then()));
    }

    /** One event per account the guardrail applies to now ({@code created} or {@code updated}) or applied to before ({@code deleted}). */
    private Mono<Void> announce(Guardrail stored, Guardrail before, String actor) {
        Flux<String> now = targets(stored);
        Flux<String> then = before == null ? Flux.empty() : targets(before);
        return Mono.zip(now.collectList(), then.collectList()).flatMap(t -> {
            Set<String> applying = new LinkedHashSet<>(t.getT1());
            List<Mono<Void>> changes = new ArrayList<>();
            for (String account : applying) {
                changes.add(events.changed(IdentityEvents.Subject.GUARDRAIL, stored.guardrailId(), account, actor, stored.version(),
                        before == null ? "created" : "updated", stored));
            }
            for (String account : t.getT2()) {
                if (!applying.contains(account)) {
                    changes.add(events.changed(IdentityEvents.Subject.GUARDRAIL, stored.guardrailId(), account, actor, stored.version(), "deleted", null));
                }
            }
            return Flux.concat(changes).then();
        });
    }

    /** The accounts a guardrail applies to: every account for {@code *}, else the ones named that exist. */
    private Flux<String> targets(Guardrail guardrail) {
        if (guardrail.everyAccount()) {
            return accounts.findAll().map(Account::accountId);
        }
        return Flux.fromIterable(guardrail.accounts()).concatMap(id -> accounts.findById(id).map(Account::accountId));
    }

    /** A 400 that names every target that is no account, as an unknown member of a group is. */
    private Mono<Void> knownAccounts(List<String> targets) {
        if (targets == null || targets.contains(Guardrail.EVERY_ACCOUNT)) {
            return Mono.empty();
        }
        return Flux.range(0, targets.size())
                .concatMap(i -> accounts.findById(targets.get(i)).hasElement()
                        .map(found -> found ? List.<Problem>of() : List.of(Problem.error("unknown", "accounts[" + i + "]", "'" + targets.get(i) + "' is no account"))))
                .flatMapIterable(problems -> problems)
                .collectList()
                .flatMap(problems -> problems.isEmpty() ? Mono.empty() : Mono.error(new InvalidInputException("the accounts are not valid", problems)));
    }

    private static void checkTargets(Names.Check check, List<String> targets, boolean required) {
        if (targets == null) {
            check.require(!required, "accounts", "accounts is [\"*\"] or a list of account ids");
            return;
        }
        check.require(!targets.isEmpty(), "accounts", "accounts is [\"*\"] or a list of account ids");
        check.require(!targets.contains(Guardrail.EVERY_ACCOUNT) || targets.size() == 1, "accounts", "\"*\" stands alone");
        for (int i = 0; i < targets.size(); i++) {
            String t = targets.get(i);
            check.require(Guardrail.EVERY_ACCOUNT.equals(t) || AccountScope.isAccountId(t), "accounts[" + i + "]", "an account id is twelve digits");
        }
        check.require(new LinkedHashSet<>(targets).size() == targets.size(), "accounts", "an account is named once");
    }
}
