package com.iaf.console.identity.iam.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accounts.ports.outbound.AccountProvisioningPort;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.IdentityProperties;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.adapters.memory.InMemoryGroupStore;
import com.iaf.console.identity.iam.adapters.memory.InMemoryPolicyStore;
import com.iaf.console.identity.iam.adapters.memory.InMemoryRoleStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoGroupStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoPolicyStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoRoleStore;
import com.iaf.console.identity.iam.adapters.platform.PlatformActionCatalog;
import com.iaf.console.identity.iam.application.GroupCommands;
import com.iaf.console.identity.iam.application.GroupQueries;
import com.iaf.console.identity.iam.application.IamProvisioning;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.ManagedPolicyUpgrade;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.application.PolicyCommands;
import com.iaf.console.identity.iam.application.PolicyQueries;
import com.iaf.console.identity.iam.application.RoleCommands;
import com.iaf.console.identity.iam.application.RoleQueries;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.platform.client.PlatformPort;

/** The IAM slice's beans. */
@Configuration(proxyBeanMethods = false)
public class IamConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public GroupStore mongoGroupStore(Mongo mongo) {
        return new MongoGroupStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public GroupStore inMemoryGroupStore() {
        return new InMemoryGroupStore();
    }

    @Bean
    @Profile("mongo")
    @Primary
    public RoleStore mongoRoleStore(Mongo mongo) {
        return new MongoRoleStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public RoleStore inMemoryRoleStore() {
        return new InMemoryRoleStore();
    }

    @Bean
    @Profile("mongo")
    @Primary
    public PolicyStore mongoPolicyStore(Mongo mongo) {
        return new MongoPolicyStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public PolicyStore inMemoryPolicyStore() {
        return new InMemoryPolicyStore();
    }

    @Bean
    public ActionCatalogPort actionCatalogPort(PlatformPort platform, Clock clock, IdentityProperties properties) {
        return new PlatformActionCatalog(platform, clock, properties.platform().catalogRefresh());
    }

    @Bean
    public IamSupport iamSupport(IdentityProperties properties) {
        return new IamSupport(properties.partition());
    }

    @Bean
    public Memberships memberships(MembershipStore memberships, GroupStore groups, IdentityEvents events, Clock clock) {
        return new Memberships(memberships, groups, events, clock);
    }

    @Bean
    public GroupCommands groupCommands(GroupStore groups, RoleStore roles, MembershipStore memberships, Memberships membership, AccessGuard guard,
            IdentityEvents events, IamSupport support, Ids ids, Clock clock) {
        return new GroupCommands(groups, roles, memberships, membership, guard, events, support, ids, clock);
    }

    @Bean
    public GroupQueries groupQueries(GroupStore groups, MembershipStore memberships, AccessGuard guard, IamSupport support) {
        return new GroupQueries(groups, memberships, guard, support);
    }

    @Bean
    public RoleCommands roleCommands(RoleStore roles, PolicyStore policies, GroupStore groups, AccessGuard guard, IdentityEvents events, IamSupport support,
            Ids ids, Clock clock) {
        return new RoleCommands(roles, policies, groups, guard, events, support, ids, clock);
    }

    @Bean
    public RoleQueries roleQueries(RoleStore roles, AccessGuard guard, IamSupport support) {
        return new RoleQueries(roles, guard, support);
    }

    @Bean
    public PolicyCommands policyCommands(PolicyStore policies, RoleStore roles, ActionCatalogPort catalog, AccessGuard guard, IdentityEvents events,
            IamSupport support, Ids ids, Clock clock) {
        return new PolicyCommands(policies, roles, catalog, guard, events, support, ids, clock);
    }

    @Bean
    public PolicyQueries policyQueries(PolicyStore policies, AccessGuard guard, IamSupport support) {
        return new PolicyQueries(policies, guard, support);
    }

    @Bean
    public AccountProvisioningPort accountProvisioningPort(PolicyStore policies, RoleStore roles, GroupStore groups, UserStore users,
            MembershipStore memberships, IdentityEvents events, IamSupport support, Ids ids, Clock clock) {
        return new IamProvisioning(policies, roles, groups, users, memberships, events, support, ids, clock);
    }

    @Bean
    public ManagedPolicyUpgrade managedPolicyUpgrade(AccountStore accounts, PolicyStore policies, IdentityEvents events, IamSupport support, Clock clock) {
        return new ManagedPolicyUpgrade(accounts, policies, events, support, clock);
    }

    @Bean
    public ManagedPolicyUpgradeOnStart managedPolicyUpgradeOnStart(ManagedPolicyUpgrade upgrade) {
        return new ManagedPolicyUpgradeOnStart(upgrade);
    }
}
