package com.iaf.console.identity.principals.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.LifecycleProperties;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.common.auth.Caller;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.adapters.directory.InMemoryDirectory;
import com.iaf.console.identity.principals.adapters.directory.ScimDirectory;
import com.iaf.console.identity.principals.adapters.directory.ScimProperties;
import com.iaf.console.identity.principals.adapters.memory.InMemoryMembershipStore;
import com.iaf.console.identity.principals.adapters.memory.InMemoryUserStore;
import com.iaf.console.identity.principals.adapters.mongo.MongoMembershipStore;
import com.iaf.console.identity.principals.adapters.mongo.MongoUserStore;
import com.iaf.console.identity.principals.application.GroupSync;
import com.iaf.console.identity.principals.application.PrincipalCommands;
import com.iaf.console.identity.principals.application.PrincipalQueries;
import com.iaf.console.identity.principals.ports.outbound.DirectoryPort;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.mongo.Mongo;

/** The principals slice's beans: stores and directory by profile, the commands and queries, the group sync and its schedule. */
@Configuration(proxyBeanMethods = false)
public class PrincipalsConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public UserStore mongoUserStore(Mongo mongo) {
        return new MongoUserStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public UserStore inMemoryUserStore() {
        return new InMemoryUserStore();
    }

    @Bean
    @Profile("mongo")
    @Primary
    public MembershipStore mongoMembershipStore(Mongo mongo) {
        return new MongoMembershipStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public MembershipStore inMemoryMembershipStore() {
        return new InMemoryMembershipStore();
    }

    @Bean
    public PrincipalQueries principalQueries(UserStore users, MembershipStore memberships, GroupStore groups, RoleStore roles, AccountStore accounts,
            AccessGuard guard, IamSupport support, ElevationStore elevations, Clock clock) {
        return new PrincipalQueries(users, memberships, groups, roles, accounts, guard, support, elevations, clock);
    }

    @Bean
    public PrincipalCommands principalCommands(UserStore users, MembershipStore memberships, GroupStore groups, AccountStore accounts, Memberships membership,
            PrincipalQueries queries, AccessGuard guard, IdentityEvents events, IamSupport support, Ids ids, Clock clock) {
        return new PrincipalCommands(users, memberships, groups, accounts, membership, queries, guard, events, support, ids, clock);
    }

    @Bean
    @Profile("scim")
    public DirectoryPort scimDirectory(WebClient.Builder web, ScimProperties properties) {
        return new ScimDirectory(web, properties);
    }

    @Bean
    @Profile("!scim")
    public InMemoryDirectory inMemoryDirectory() {
        return new InMemoryDirectory();
    }

    @Bean
    public GroupSync groupSync(AccountStore accounts, GroupStore groups, MembershipStore memberships, DirectoryPort directory, Memberships membership,
            IdentityEvents events, Clock clock) {
        return new GroupSync(accounts, groups, memberships, directory, membership, events, clock);
    }

    @Bean
    public GroupSyncSchedule groupSyncSchedule(GroupSync sync, LifecycleProperties properties, Clock clock) {
        LifecycleProperties.GroupSync settings = properties.groupSync();
        String actor = Caller.internal("console-identity-service", "global").actor();
        return new GroupSyncSchedule(() -> sync.run(actor), settings.cron(), settings.zone(), clock, settings.enabled());
    }
}
