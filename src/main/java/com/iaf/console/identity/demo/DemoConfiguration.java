package com.iaf.console.identity.demo;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accounts.application.AccountCommands;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.iam.application.GroupCommands;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.PolicyCommands;
import com.iaf.console.identity.iam.application.RoleCommands;
import com.iaf.console.identity.principals.adapters.directory.InMemoryDirectory;
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.ports.outbound.UserStore;

/** The {@code demo} profile: seeds the demo accounts, people and directory once the service is up. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfiguration {

    @Bean
    public DemoSeed demoSeed(AccountCommands accounts, AccountStore accountStore, PrincipalCommands principals, UserStore users, PolicyCommands policies,
            RoleCommands roles, GroupCommands groups, IamSupport support, Clock clock) {
        return new DemoSeed(accounts, accountStore, principals, users, policies, roles, groups, support, clock);
    }

    /** Seeds the accounts and people, and the in-memory directory (absent under {@code scim}) with the demo's teams. */
    @Bean
    public ApplicationRunner demoSeedRunner(DemoSeed seed, ObjectProvider<InMemoryDirectory> directory) {
        return args -> {
            seed.seed().block();
            directory.ifAvailable(d -> DemoSeed.directory().forEach(d::put));
        };
    }
}
