package com.iaf.console.identity.demo;

import java.time.Clock;

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
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.ports.outbound.UserStore;

/** The {@code demo} profile: seeds the demo accounts and people once the service is up. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoConfiguration {

    @Bean
    public DemoSeed demoSeed(AccountCommands accounts, AccountStore accountStore, PrincipalCommands principals, UserStore users, PolicyCommands policies,
            RoleCommands roles, GroupCommands groups, IamSupport support, Clock clock) {
        return new DemoSeed(accounts, accountStore, principals, users, policies, roles, groups, support, clock);
    }

    @Bean
    public ApplicationRunner demoSeedRunner(DemoSeed seed) {
        return args -> seed.seed().block();
    }
}
