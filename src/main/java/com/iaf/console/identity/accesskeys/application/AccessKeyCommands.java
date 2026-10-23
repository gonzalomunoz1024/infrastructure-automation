package com.iaf.console.identity.accesskeys.application;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

import com.iaf.console.identity.accesskeys.domain.AccessKey;
import com.iaf.console.identity.accesskeys.domain.AccessKeyCreated;
import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.identity.accounts.ports.outbound.AccountClosurePort;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityChange;
import com.iaf.console.identity.events.IdentityChangeListener;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The writes on access keys. A user manages their own keys; another user's keys take
 * {@code iam:create}, {@code iam:update} or {@code iam:delete} on the key. At most two usable keys
 * per user. A removed user's keys are revoked when the {@code UserChanged deleted} event runs, and
 * a closed account's usable keys when the account closes. Keys are minted in active accounts only:
 * creating one in a suspended or closed account is a 409, even one's own.
 */
public final class AccessKeyCommands implements IdentityChangeListener, AccountClosurePort {

    public static final int MAX_USABLE_PER_USER = 2;
    private static final String KIND = "access-key";

    private final AccessKeyStore keys;
    private final MembershipStore memberships;
    private final AccountStore accounts;
    private final AccessGuard guard;
    private final Supplier<IdentityEvents> events;
    private final IamSupport support;
    private final Ids ids;
    private final Clock clock;

    public AccessKeyCommands(AccessKeyStore keys, MembershipStore memberships, AccountStore accounts, AccessGuard guard, Supplier<IdentityEvents> events,
            IamSupport support, Ids ids, Clock clock) {
        this.keys = keys;
        this.memberships = memberships;
        this.accounts = accounts;
        this.guard = guard;
        this.events = events;
        this.support = support;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<AccessKeyCreated> create(Caller caller, String accountId, CreateAccessKey input) {
        return support.scope(caller, accountId).flatMap(scope -> {
            new Names.Check().require(input.userId() != null && !input.userId().isBlank(), "userId", "userId is required")
                    .require(input.description() == null || input.description().length() <= 128, "description", "description is at most 128 characters")
                    .require(input.expiresAt() == null || input.expiresAt().isAfter(clock.instant()), "expiresAt", "expiresAt is in the future")
                    .done("the access key");
            Mono<Void> allowed = own(caller, input.userId()) ? Mono.empty() : guard.require(caller, accountId, "iam:create", support.anyResource(accountId, KIND));
            return allowed
                    .then(accounts.find(scope).switchIfEmpty(Mono.error(NotFoundException.of("account", accountId)))
                            .flatMap(account -> account.isActive() ? Mono.<Void>empty() : Mono.error(new ConflictException("the account is " + account.status()))))
                    .then(memberships.find(scope, input.userId()).switchIfEmpty(Mono.error(NotFoundException.of("user", input.userId()))))
                    .then(keys.findByUser(scope, input.userId()).filter(k -> k.usable(clock.instant())).count())
                    .flatMap(usable -> usable >= MAX_USABLE_PER_USER
                            ? Mono.error(new ConflictException("a user has at most " + MAX_USABLE_PER_USER + " active access keys"))
                            : Mono.empty())
                    .then(Mono.defer(() -> {
                        Instant now = clock.instant();
                        String secret = ids.secret();
                        AccessKey key = AccessKey.create(ids.accessKey(), accountId, input.userId(), input.description(), secret, input.expiresAt(), now);
                        return keys.insert(key)
                                .flatMap(stored -> events.get().changed(IdentityEvents.Subject.ACCESS_KEY, stored.keyId(), accountId, caller.actor(), stored.version(),
                                        "created", stored.view(now)).thenReturn(stored.created(secret, now)));
                    }));
        });
    }

    public Mono<AccessKeyCreated> rotate(Caller caller, String accountId, String keyId) {
        return support.scope(caller, accountId).flatMap(scope -> keys.find(scope, keyId)
                .switchIfEmpty(Mono.error(NotFoundException.of("access key", keyId)))
                .flatMap(key -> {
                    Mono<Void> allowed = own(caller, key.userId()) ? Mono.empty()
                            : guard.require(caller, accountId, "iam:update", support.resource(accountId, KIND, keyId));
                    Instant now = clock.instant();
                    if (!key.usable(now)) {
                        return allowed.then(Mono.error(new ConflictException("the key is " + key.status(now))));
                    }
                    String secret = ids.secret();
                    AccessKey rotated = key.rotated(secret, now);
                    return allowed.then(keys.update(rotated, key.version()))
                            .switchIfEmpty(Mono.error(new ConflictException("the key moved on; read it again")))
                            .flatMap(stored -> events.get().changed(IdentityEvents.Subject.ACCESS_KEY, stored.keyId(), accountId, caller.actor(), stored.version(),
                                    "rotated", stored.view(now)).thenReturn(stored.created(secret, now)));
                }));
    }

    public Mono<Void> revoke(Caller caller, String accountId, String keyId) {
        return support.scope(caller, accountId).flatMap(scope -> keys.find(scope, keyId)
                .switchIfEmpty(Mono.error(NotFoundException.of("access key", keyId)))
                .flatMap(key -> {
                    Mono<Void> allowed = own(caller, key.userId()) ? Mono.empty()
                            : guard.require(caller, accountId, "iam:delete", support.resource(accountId, KIND, keyId));
                    return allowed.then(revoke(scope, key, caller.actor()));
                }));
    }

    private Mono<Void> revoke(AccountScope scope, AccessKey key, String actor) {
        Instant now = clock.instant();
        if (key.revokedAt() != null) {
            return Mono.empty();
        }
        AccessKey revoked = key.revoked(now);
        return keys.update(revoked, key.version())
                .switchIfEmpty(Mono.error(new ConflictException("the key moved on; read it again")))
                .flatMap(stored -> events.get().changed(IdentityEvents.Subject.ACCESS_KEY, stored.keyId(), scope.accountId(), actor, stored.version(), "revoked",
                        stored.view(now)));
    }

    /** A key presented to the console: the key when the secret part opens it; empty otherwise. Touches {@code lastUsedAt}. */
    public Mono<AccessKey> verify(String keyId, String secretPart) {
        return keys.findByKeyId(keyId)
                .filter(key -> key.accepts(secretPart, clock.instant()))
                .flatMap(key -> keys.update(key.used(clock.instant()), key.version()).defaultIfEmpty(key));
    }

    /** A removed user's keys are revoked. */
    @Override
    public Mono<Void> changed(IdentityChange change) {
        if (!"UserChanged".equals(change.type()) || !"deleted".equals(change.action())) {
            return Mono.empty();
        }
        AccountScope scope = AccountScope.of(change.accountId(), "global", "svc:console-identity-service");
        return keys.findByUser(scope, change.aggregateId()).concatMap(key -> revoke(scope, key, "svc:console-identity-service")).then();
    }

    /** A closed account's usable keys are revoked, each with its {@code AccessKeyChanged revoked}; a key that moved on meanwhile is read again. */
    @Override
    public Mono<Void> accountClosed(AccountScope scope, String actor) {
        return Flux.defer(() -> keys.findAll(scope))
                .filter(key -> key.usable(clock.instant()))
                .concatMap(key -> revoke(scope, key, actor))
                .then()
                .retryWhen(Retry.max(2).filter(ConflictException.class::isInstance));
    }

    private static boolean own(Caller caller, String userId) {
        return caller.isUser() && caller.id().equals(userId);
    }
}
