package com.iaf.console.identity.accesskeys.adapters;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accesskeys.adapters.memory.InMemoryAccessKeyStore;
import com.iaf.console.identity.accesskeys.adapters.mongo.MongoAccessKeyStore;
import com.iaf.console.identity.accesskeys.application.AccessKeyCommands;
import com.iaf.console.identity.accesskeys.application.AccessKeyQueries;
import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.mongo.Mongo;

/** The access keys slice's beans. */
@Configuration(proxyBeanMethods = false)
public class AccessKeysConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public AccessKeyStore mongoAccessKeyStore(Mongo mongo) {
        return new MongoAccessKeyStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public AccessKeyStore inMemoryAccessKeyStore() {
        return new InMemoryAccessKeyStore();
    }

    @Bean
    public AccessKeyCommands accessKeyCommands(AccessKeyStore keys, MembershipStore memberships, AccountStore accounts, AccessGuard guard,
            ObjectProvider<IdentityEvents> events, IamSupport support, Ids ids, Clock clock) {
        // the commands listen to the events they also append, so the appender is looked up when first needed
        return new AccessKeyCommands(keys, memberships, accounts, guard, events::getObject, support, ids, clock);
    }

    @Bean
    public AccessKeyQueries accessKeyQueries(AccessKeyStore keys, AccessGuard guard, IamSupport support, Clock clock) {
        return new AccessKeyQueries(keys, guard, support, clock);
    }
}
