package com.iaf.console.identity.authz.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.authz.adapters.inprocess.InProcessDecisions;
import com.iaf.console.identity.authz.adapters.memory.InMemoryAuthzVersions;
import com.iaf.console.identity.authz.adapters.mongo.MongoAuthzVersions;
import com.iaf.console.identity.authz.adapters.opa.OpaHttpDecisions;
import com.iaf.console.identity.authz.application.Authorizer;
import com.iaf.console.identity.authz.application.AuthzVersions;
import com.iaf.console.identity.authz.application.Bundles;
import com.iaf.console.identity.authz.application.Compiler;
import com.iaf.console.identity.authz.application.Snapshots;
import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;
import com.iaf.console.identity.authz.ports.outbound.DecisionPort;
import com.iaf.console.identity.common.IdentityProperties;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.PolicyStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.mongo.Mongo;

/** The authorization slice's beans: versions by profile, the decision port by profile, the compiler, snapshot, bundle and authorizer. */
@Configuration(proxyBeanMethods = false)
public class AuthzConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public AuthzVersionPort mongoAuthzVersions(Mongo mongo) {
        return new MongoAuthzVersions(mongo);
    }

    @Bean
    @Profile("!mongo")
    public AuthzVersionPort inMemoryAuthzVersions() {
        return new InMemoryAuthzVersions();
    }

    @Bean
    public Compiler authzCompiler(AccountStore accounts, UserStore users, MembershipStore memberships, GroupStore groups, RoleStore roles,
            PolicyStore policies, AuthzVersionPort versions) {
        return new Compiler(accounts, users, memberships, groups, roles, policies, versions);
    }

    @Bean
    @Order(10)
    public AuthzVersions authzVersions(AuthzVersionPort versions) {
        return new AuthzVersions(versions);
    }

    @Bean
    @Order(20)
    public Snapshots authzSnapshots(Compiler compiler, AuthzVersionPort versions, Clock clock, IdentityProperties properties) {
        return new Snapshots(compiler, versions, clock, properties.authz().cacheTtl());
    }

    @Bean
    public Bundles authzBundles(Snapshots snapshots, ObjectMapper json) {
        return new Bundles(snapshots, json);
    }

    @Bean
    @Profile("opa")
    @Primary
    public DecisionPort opaDecisions(WebClient.Builder webClient, IdentityProperties properties, ObjectMapper json) {
        return new OpaHttpDecisions(webClient, properties.authz().opaUrl(), json);
    }

    @Bean
    @Profile("!opa")
    public DecisionPort inProcessDecisions(Snapshots snapshots) {
        return new InProcessDecisions(snapshots);
    }

    @Bean
    public Authorizer authorizer(DecisionPort decisions, Snapshots snapshots) {
        return new Authorizer(decisions, snapshots);
    }
}
