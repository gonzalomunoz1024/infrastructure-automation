package com.iaf.console.identity.accounts.adapters;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.identity.accounts.adapters.memory.InMemoryAccountStore;
import com.iaf.console.identity.accounts.adapters.mongo.MongoAccountStore;
import com.iaf.console.identity.accounts.adapters.platform.PlatformIdentityAdapter;
import com.iaf.console.identity.accounts.adapters.secrets.InMemorySecrets;
import com.iaf.console.identity.accounts.adapters.secrets.VaultSecrets;
import com.iaf.console.identity.accounts.application.AccountClosure;
import com.iaf.console.identity.accounts.application.AccountCommands;
import com.iaf.console.identity.accounts.application.AccountQueries;
import com.iaf.console.identity.accounts.ports.outbound.AccountClosurePort;
import com.iaf.console.identity.accounts.ports.outbound.AccountProvisioningPort;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.accounts.ports.outbound.MembershipLookupPort;
import com.iaf.console.identity.accounts.ports.outbound.PlatformIdentityPort;
import com.iaf.console.identity.accounts.ports.outbound.SecretsPort;
import com.iaf.console.identity.common.IdentityProperties;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.platform.client.PlatformPort;

/** The accounts slice's beans: stores by profile, the platform and secrets adapters, the commands, the closure and the queries. */
@Configuration(proxyBeanMethods = false)
public class AccountsConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public AccountStore mongoAccountStore(Mongo mongo) {
        return new MongoAccountStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public AccountStore inMemoryAccountStore() {
        return new InMemoryAccountStore();
    }

    @Bean
    @Profile("vault")
    @Primary
    public SecretsPort vaultSecrets(IdentityProperties properties, WebClient.Builder webClient) {
        IdentityProperties.Vault vault = properties.vault();
        return new VaultSecrets(webClient, vault.baseUrl(), vault.token(), vault.mount(), vault.pathPrefix());
    }

    @Bean
    @Profile("!vault")
    public SecretsPort inMemorySecrets() {
        return new InMemorySecrets();
    }

    @Bean
    public PlatformIdentityPort platformIdentityPort(PlatformPort platform) {
        return new PlatformIdentityAdapter(platform);
    }

    @Bean
    public AccountCommands accountCommands(AccountStore accounts, PlatformIdentityPort platform, SecretsPort secrets, AccountProvisioningPort provisioning,
            IdentityEvents events, AccessGuard guard, Ids ids, Clock clock, IdentityProperties properties) {
        return new AccountCommands(accounts, platform, secrets, provisioning, events, guard, ids, clock, properties.partition(), properties.region());
    }

    /** The closure, with every slice's closure step (looked up per closure, so no slice's beans wait on this one). */
    @Bean
    public AccountClosure accountClosure(AccountStore accounts, ObjectProvider<AccountClosurePort> steps, IdentityEvents events, Clock clock) {
        return new AccountClosure(accounts, () -> steps.orderedStream().toList(), events, clock);
    }

    @Bean
    public AccountQueries accountQueries(AccountStore accounts, MembershipLookupPort memberships, SecretsPort secrets) {
        return new AccountQueries(accounts, memberships, secrets);
    }
}
