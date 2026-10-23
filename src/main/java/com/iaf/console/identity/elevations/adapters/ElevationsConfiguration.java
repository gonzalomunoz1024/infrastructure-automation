package com.iaf.console.identity.elevations.adapters;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.elevations.adapters.memory.InMemoryElevationStore;
import com.iaf.console.identity.elevations.adapters.mongo.MongoElevationStore;
import com.iaf.console.identity.elevations.application.ElevationCommands;
import com.iaf.console.identity.elevations.application.ElevationExpiry;
import com.iaf.console.identity.elevations.application.ElevationQueries;
import com.iaf.console.identity.elevations.domain.ElevationRules;
import com.iaf.console.identity.elevations.ports.outbound.ElevationStore;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.identity.principals.ports.outbound.UserStore;
import com.iaf.console.kit.mongo.Mongo;

/** The elevations slice's beans. */
@Configuration(proxyBeanMethods = false)
public class ElevationsConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public ElevationStore mongoElevationStore(Mongo mongo) {
        return new MongoElevationStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public ElevationStore inMemoryElevationStore() {
        return new InMemoryElevationStore();
    }

    @Bean
    public ElevationRules elevationRules(ElevationProperties properties) {
        return properties.rules();
    }

    @Bean
    public ElevationCommands elevationCommands(ElevationStore elevations, MembershipStore memberships, UserStore users, GroupStore groups, RoleStore roles,
            AccountStore accounts, AccessGuard guard, IamSupport support, ObjectProvider<IdentityEvents> events, ElevationRules rules, Ids ids, Clock clock) {
        // the commands listen to the events they also append, so the appender is looked up when first needed
        return new ElevationCommands(elevations, memberships, users, groups, roles, accounts, guard, support, events::getObject, rules, ids, clock);
    }

    @Bean
    public ElevationQueries elevationQueries(ElevationStore elevations, AccessGuard guard, IamSupport support, ElevationRules rules, Clock clock) {
        return new ElevationQueries(elevations, guard, support, rules, clock);
    }

    @Bean
    public ElevationExpiry elevationExpiry(ElevationStore elevations, ObjectProvider<IdentityEvents> events, ElevationRules rules) {
        return new ElevationExpiry(elevations, events::getObject, rules);
    }

    @Bean
    public ElevationExpirySchedule elevationExpirySchedule(ElevationExpiry expiry, Clock clock, ElevationProperties properties) {
        return new ElevationExpirySchedule(expiry, clock, properties.sweepEvery());
    }
}
