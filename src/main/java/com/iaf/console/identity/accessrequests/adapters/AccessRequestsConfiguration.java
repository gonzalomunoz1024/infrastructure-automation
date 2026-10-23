package com.iaf.console.identity.accessrequests.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accessrequests.adapters.memory.InMemoryAccessRequestStore;
import com.iaf.console.identity.accessrequests.adapters.mongo.MongoAccessRequestStore;
import com.iaf.console.identity.accessrequests.application.AccessRequestCommands;
import com.iaf.console.identity.accessrequests.application.AccessRequestQueries;
import com.iaf.console.identity.accessrequests.ports.outbound.AccessRequestStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.application.PrincipalQueries;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.mongo.Mongo;

/** The access requests slice's beans. */
@Configuration(proxyBeanMethods = false)
public class AccessRequestsConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public AccessRequestStore mongoAccessRequestStore(Mongo mongo) {
        return new MongoAccessRequestStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public AccessRequestStore inMemoryAccessRequestStore() {
        return new InMemoryAccessRequestStore();
    }

    @Bean
    public AccessRequestCommands accessRequestCommands(AccessRequestStore requests, MembershipStore memberships, GroupStore groups, RoleStore roles,
            Memberships membership, PrincipalQueries principals, AccessGuard guard, IamSupport support, Ids ids, Clock clock) {
        return new AccessRequestCommands(requests, memberships, groups, roles, membership, principals, guard, support, ids, clock);
    }

    @Bean
    public AccessRequestQueries accessRequestQueries(AccessRequestStore requests, AccessGuard guard, IamSupport support, Clock clock) {
        return new AccessRequestQueries(requests, guard, support, clock);
    }
}
