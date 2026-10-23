package com.iaf.console.identity.accounts.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.domain.PlatformSecret;
import com.iaf.console.identity.accounts.ports.outbound.AccountProvisioningPort;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.accounts.ports.outbound.PlatformIdentityPort;
import com.iaf.console.identity.accounts.ports.outbound.SecretsPort;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.Names;
import com.iaf.console.identity.common.Versions;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The writes on accounts. Creating one mints the twelve-digit id, asks the platform for the
 * account's console service account (P-03), puts its token in the secrets store, stores the
 * account, provisions its built-in IAM and first owner, and appends {@code AccountChanged}. A
 * change takes no {@code closed} status: an account closes through {@link AccountClosure}, and a
 * closed account's status no longer changes.
 */
public final class AccountCommands {

    private static final Logger log = LoggerFactory.getLogger(AccountCommands.class);
    private static final int ID_ATTEMPTS = 5;
    private static final int MAX_COST_CENTRE = 64;
    private static final String COST_CENTRE_RULE = "costCentre is at most 64 characters";

    private final AccountStore accounts;
    private final PlatformIdentityPort platform;
    private final SecretsPort secrets;
    private final AccountProvisioningPort provisioning;
    private final IdentityEvents events;
    private final Ids ids;
    private final Clock clock;
    private final String defaultRegion;

    public AccountCommands(AccountStore accounts, PlatformIdentityPort platform, SecretsPort secrets, AccountProvisioningPort provisioning,
            IdentityEvents events, Ids ids, Clock clock, String defaultRegion) {
        this.accounts = accounts;
        this.platform = platform;
        this.secrets = secrets;
        this.provisioning = provisioning;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.defaultRegion = defaultRegion;
    }

    /** Internal principal only; a user's call answers 404 as the contract says for every internal operation. */
    public Mono<Account> create(Caller caller, CreateAccount command) {
        return create(caller, command, null);
    }

    /** Creates with a given id (the demo seed's accounts match platform-fake's world); null mints one. */
    public Mono<Account> create(Caller caller, CreateAccount command, String fixedAccountId) {
        if (!caller.isInternal()) {
            return Mono.error(new NotFoundException("no such operation"));
        }
        Names.Check check = new Names.Check()
                .require(command.name() != null && !command.name().isBlank() && command.name().length() <= 64, "name", "name is required, at most 64 characters")
                .require(command.owningTeam() != null && Names.isName(command.owningTeam()), "owningTeam", "owningTeam is the platform team's name")
                .require(Names.isEmail(command.ownerEmail()), "ownerEmail", "ownerEmail must be an email address")
                .require(command.defaultRegion() == null || Names.isName(command.defaultRegion()), "defaultRegion", "defaultRegion is a region name")
                .require(costCentreFits(command.costCentre()), "costCentre", COST_CENTRE_RULE);
        check.done("the account");
        String region = command.defaultRegion() == null ? defaultRegion : command.defaultRegion();
        return accounts.existsByName(command.name().trim())
                .flatMap(exists -> exists ? Mono.error(new ConflictException("an account named '" + command.name().trim() + "' exists")) : Mono.empty())
                .then(Mono.defer(() -> fixedAccountId == null ? createWithFreshId(caller, command, region, 1)
                        : accounts.findById(fixedAccountId)
                                .flatMap(taken -> Mono.<Account>error(new ConflictException("the account " + fixedAccountId + " exists")))
                                .switchIfEmpty(Mono.defer(() -> provisionAndStore(caller, command, region, fixedAccountId)))));
    }

    private Mono<Account> createWithFreshId(Caller caller, CreateAccount command, String region, int attempt) {
        String accountId = ids.accountId();
        return accounts.findById(accountId)
                .flatMap(taken -> attempt >= ID_ATTEMPTS
                        ? Mono.<Account>error(new IllegalStateException("could not mint a free account id"))
                        : createWithFreshId(caller, command, region, attempt + 1))
                .switchIfEmpty(Mono.defer(() -> provisionAndStore(caller, command, region, accountId)));
    }

    private Mono<Account> provisionAndStore(Caller caller, CreateAccount command, String region, String accountId) {
        String name = command.name().trim();
        return platform.createConsoleServiceAccount(accountId, name, command.owningTeam())
                .flatMap(created -> secrets.put(new PlatformSecret(accountId, created.id(), created.token(), clock.instant()))
                        .then(Mono.defer(() -> {
                            Account account = Account.create(accountId, name, command.owningTeam(), region, created.id(), command.costCentre(), clock.instant());
                            return accounts.insert(account);
                        }))
                        .onErrorResume(e -> platform.delete(created.id()).onErrorResume(x -> {
                            log.warn("could not delete platform service account {} after a failed account creation: {}", created.id(), x.toString());
                            return Mono.empty();
                        }).then(Mono.error(e))))
                .flatMap(account -> provisioning.provision(account, Names.normalizeEmail(command.ownerEmail()), caller.actor())
                        .then(events.changed(IdentityEvents.Subject.ACCOUNT, account.accountId(), account.accountId(), caller.actor(), account.version(),
                                "created", account))
                        .thenReturn(account));
    }

    public Mono<Account> update(Caller caller, String accountId, UpdateAccount command, Long ifMatch) {
        if (!caller.covers(accountId)) {
            return Mono.error(NotFoundException.of("account", accountId));
        }
        new Names.Check()
                .require(command.name() == null || (!command.name().isBlank() && command.name().length() <= 64), "name", "name is at most 64 characters")
                .require(command.defaultRegion() == null || Names.isName(command.defaultRegion()), "defaultRegion", "defaultRegion is a region name")
                .require(caller.isInternal() || command.status() == null, "status", "only the console's own services change an account's status")
                .require(command.status() != AccountStatus.closed, "status", "an account closes through POST /v1/accounts/{accountId}:close")
                .require(costCentreFits(command.costCentre()), "costCentre", COST_CENTRE_RULE)
                .done("the account");
        AccountScope scope = caller.scope(accountId);
        return accounts.find(scope)
                .switchIfEmpty(Mono.error(NotFoundException.of("account", accountId)))
                .flatMap(current -> {
                    Versions.check(ifMatch, current.version(), "the account");
                    if (current.isClosed() && command.status() != null) {
                        return Mono.error(new ConflictException("the account is closed; its status no longer changes"));
                    }
                    String newName = command.name() == null ? null : command.name().trim();
                    Mono<Void> nameFree = newName == null || newName.equals(current.name()) ? Mono.empty()
                            : accounts.existsByName(newName).flatMap(exists -> exists
                                    ? Mono.error(new ConflictException("an account named '" + newName + "' exists")) : Mono.empty());
                    String costCentre = command.costCentre() == null ? null : command.costCentre().trim();
                    Account changed = current.changed(newName, command.status(), command.defaultRegion(), costCentre, clock.instant());
                    String action = command.status() == AccountStatus.suspended && current.status() != AccountStatus.suspended ? "suspended" : "updated";
                    return nameFree.then(accounts.update(changed, current.version()))
                            .switchIfEmpty(Mono.error(new ConflictException("the account moved on; read it again")))
                            .flatMap(stored -> events.changed(IdentityEvents.Subject.ACCOUNT, stored.accountId(), stored.accountId(), caller.actor(),
                                    stored.version(), action, stored).thenReturn(stored));
                });
    }

    /** A cost centre is at most 64 characters once trimmed; a blank one is none. */
    private static boolean costCentreFits(String costCentre) {
        return costCentre == null || costCentre.trim().length() <= MAX_COST_CENTRE;
    }
}
