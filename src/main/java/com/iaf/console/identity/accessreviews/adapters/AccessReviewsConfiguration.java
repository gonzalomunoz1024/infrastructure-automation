package com.iaf.console.identity.accessreviews.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accessreviews.adapters.memory.InMemoryAccessReviewItemStore;
import com.iaf.console.identity.accessreviews.adapters.memory.InMemoryAccessReviewStore;
import com.iaf.console.identity.accessreviews.adapters.mongo.MongoAccessReviewItemStore;
import com.iaf.console.identity.accessreviews.adapters.mongo.MongoAccessReviewStore;
import com.iaf.console.identity.accessreviews.application.AccessReviewCampaigns;
import com.iaf.console.identity.accessreviews.application.AccessReviewCommands;
import com.iaf.console.identity.accessreviews.application.AccessReviewQueries;
import com.iaf.console.identity.accessreviews.application.AccessReviewSweep;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewItemStore;
import com.iaf.console.identity.accessreviews.ports.outbound.AccessReviewStore;
import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.application.Memberships;
import com.iaf.console.identity.iam.ports.outbound.GroupStore;
import com.iaf.console.identity.iam.ports.outbound.RoleStore;
import com.iaf.console.identity.principals.ports.outbound.MembershipStore;
import com.iaf.console.kit.mongo.Mongo;

/** The access reviews slice's beans. */
@Configuration(proxyBeanMethods = false)
public class AccessReviewsConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public AccessReviewStore mongoAccessReviewStore(Mongo mongo) {
        return new MongoAccessReviewStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public AccessReviewStore inMemoryAccessReviewStore() {
        return new InMemoryAccessReviewStore();
    }

    @Bean
    @Profile("mongo")
    @Primary
    public AccessReviewItemStore mongoAccessReviewItemStore(Mongo mongo) {
        return new MongoAccessReviewItemStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public AccessReviewItemStore inMemoryAccessReviewItemStore() {
        return new InMemoryAccessReviewItemStore();
    }

    @Bean
    public AccessReviewCampaigns accessReviewCampaigns(AccessReviewStore reviews, AccessReviewItemStore items, MembershipStore memberships, GroupStore groups,
            RoleStore roles, Memberships membership, IdentityEvents events, Ids ids) {
        return new AccessReviewCampaigns(reviews, items, memberships, groups, roles, membership, events, ids);
    }

    @Bean
    public AccessReviewCommands accessReviewCommands(AccessReviewStore reviews, AccessReviewItemStore items, AccountStore accounts,
            AccessReviewCampaigns campaigns, AccessGuard guard, IamSupport support, Clock clock) {
        return new AccessReviewCommands(reviews, items, accounts, campaigns, guard, support, clock);
    }

    @Bean
    public AccessReviewQueries accessReviewQueries(AccessReviewStore reviews, AccessReviewItemStore items, AccessGuard guard, IamSupport support) {
        return new AccessReviewQueries(reviews, items, guard, support);
    }

    @Bean
    public AccessReviewSweep accessReviewSweep(AccessReviewStore reviews, AccountStore accounts, AccessReviewCampaigns campaigns,
            AccessReviewProperties properties) {
        AccessReviewProperties.Scheduled scheduled = properties.scheduled();
        return new AccessReviewSweep(reviews, accounts, campaigns, scheduled.enabled(), scheduled.cadence(), scheduled.dueInDays());
    }

    @Bean
    public AccessReviewSchedule accessReviewSchedule(AccessReviewSweep sweep, Clock clock, AccessReviewProperties properties) {
        return new AccessReviewSchedule(sweep, clock, properties.sweepEvery());
    }
}
