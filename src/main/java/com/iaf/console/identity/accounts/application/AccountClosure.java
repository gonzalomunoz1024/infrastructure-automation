package com.iaf.console.identity.accounts.application;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.ports.outbound.AccountClosurePort;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Closes an account (internal principal only; a user's call answers 404). Closing is terminal. The
 * account is stored closed first, with {@code closedAt} and {@code closeReason}, so from then on
 * every call in it is refused ({@code account closed}); then each {@link AccountClosurePort} ends
 * what its slice holds (usable access keys revoked, pending access requests expired, open quota
 * requests withdrawn, each with its event); then {@code AccountChanged closed} carries the closed
 * account. Closing a closed account is a 409, once it has finished what an interrupted closure
 * left: the steps find nothing left to end, and the outbox takes an event key once.
 *
 * <p>The account's platform service account stays as it is: the platform can create, rotate and
 * delete one but not disable it, and deleting it now would leave the account's platform resources
 * without the identity they were created through before anything transfers them.
 */
public final class AccountClosure {

    private static final Logger log = LoggerFactory.getLogger(AccountClosure.class);
    private static final int MAX_REASON = 2000;

    private final AccountStore accounts;
    private final Supplier<List<AccountClosurePort>> steps;
    private final IdentityEvents events;
    private final Clock clock;

    public AccountClosure(AccountStore accounts, Supplier<List<AccountClosurePort>> steps, IdentityEvents events, Clock clock) {
        this.accounts = accounts;
        this.steps = steps;
        this.events = events;
        this.clock = clock;
    }

    public Mono<Account> close(Caller caller, String accountId, CloseAccount command) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        String reason = command == null || command.reason() == null ? null : command.reason().trim();
        new Names.Check()
                .require(reason != null && !reason.isEmpty() && reason.length() <= MAX_REASON, "reason", "reason is required, at most 2000 characters")
                .done("the closure");
        AccountScope scope = caller.scope(accountId);
        return accounts.find(scope)
                .switchIfEmpty(Mono.error(NotFoundException.of("account", accountId)))
                .flatMap(current -> current.isClosed()
                        ? finish(scope, current, caller.actor()).then(Mono.error(new ConflictException("the account is closed")))
                        : accounts.update(current.closed(reason, clock.instant()), current.version())
                                .switchIfEmpty(Mono.error(new ConflictException("the account moved on; read it again")))
                                .flatMap(closed -> finish(scope, closed, caller.actor())
                                        .doOnSuccess(v -> log.info("closed account {} for {}; its platform service account {} stays as it is",
                                                accountId, caller.actor(), closed.platformServiceAccountId()))
                                        .thenReturn(closed)));
    }

    private Mono<Void> finish(AccountScope scope, Account closed, String actor) {
        return Flux.fromIterable(steps.get())
                .concatMap(step -> step.accountClosed(scope, actor))
                .then(Mono.defer(() -> events.changed(IdentityEvents.Subject.ACCOUNT, closed.accountId(), closed.accountId(), actor, closed.version(), "closed",
                        closed)));
    }
}
