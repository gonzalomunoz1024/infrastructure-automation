package com.iaf.console.identity.accounts.application;

import java.util.List;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.accounts.domain.AccountStatus;
import com.iaf.console.identity.accounts.domain.PlatformCredentials;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.accounts.ports.outbound.MembershipLookupPort;
import com.iaf.console.identity.accounts.ports.outbound.SecretsPort;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/** The reads on accounts: one, a page, and the platform credentials for the console's own services. */
public final class AccountQueries {

    private final AccountStore accounts;
    private final MembershipLookupPort memberships;
    private final SecretsPort secrets;

    public AccountQueries(AccountStore accounts, MembershipLookupPort memberships, SecretsPort secrets) {
        this.accounts = accounts;
        this.memberships = memberships;
        this.secrets = secrets;
    }

    public Mono<Account> get(Caller caller, String accountId) {
        if (!caller.covers(accountId)) {
            return Mono.error(NotFoundException.of("account", accountId));
        }
        return accounts.find(caller.scope(accountId)).switchIfEmpty(Mono.error(NotFoundException.of("account", accountId)));
    }

    /** The internal principal lists every account; a user gets the accounts they belong to. */
    public Mono<Page<Account>> list(Caller caller, AccountStatus status, int limit, String after) {
        if (caller.isInternal()) {
            return accounts.list(status, limit, after);
        }
        return memberships.listAccountIds(caller.id())
                .flatMap(ids -> ids.isEmpty() ? Mono.just(List.<Account>of()) : accounts.listByIds(ids))
                .map(all -> Page.last(all.stream().filter(a -> status == null || a.status() == status).toList()));
    }

    /** The console-internal principal only; a user's token answers 404. */
    public Mono<PlatformCredentials> platformCredentials(Caller caller, String accountId) {
        if (!caller.isInternal()) {
            return Mono.error(NotFoundException.of("account", accountId));
        }
        return accounts.findById(accountId)
                .switchIfEmpty(Mono.error(NotFoundException.of("account", accountId)))
                .flatMap(account -> secrets.find(accountId)
                        .map(secret -> "vault".equals(secrets.source())
                                ? new PlatformCredentials(accountId, secret.serviceAccountId(), "vault", null, secrets.vaultPath(accountId), secret.issuedAt())
                                : new PlatformCredentials(accountId, secret.serviceAccountId(), "local", secret.token(), null, secret.issuedAt()))
                        .switchIfEmpty(Mono.fromSupplier(() -> new PlatformCredentials(accountId, account.platformServiceAccountId(), secrets.source(), null,
                                "vault".equals(secrets.source()) ? secrets.vaultPath(accountId) : null, null))));
    }
}
