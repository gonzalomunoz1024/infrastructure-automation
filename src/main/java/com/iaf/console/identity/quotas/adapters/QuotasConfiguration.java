package com.iaf.console.identity.quotas.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accesskeys.ports.outbound.AccessKeyStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.quotas.adapters.memory.InMemoryQuotaStore;
import com.iaf.console.identity.quotas.adapters.mongo.MongoQuotaStore;
import com.iaf.console.identity.quotas.adapters.platform.PlatformQuotaAdapter;
import com.iaf.console.identity.quotas.application.QuotaCommands;
import com.iaf.console.identity.quotas.application.QuotaQueries;
import com.iaf.console.identity.quotas.domain.QuotaRules;
import com.iaf.console.identity.quotas.ports.outbound.PlatformQuotaPort;
import com.iaf.console.identity.quotas.ports.outbound.QuotaStore;
import com.iaf.console.kit.mongo.Mongo;
import com.iaf.console.platform.client.PlatformPort;

/** The quotas slice's beans. */
@Configuration(proxyBeanMethods = false)
public class QuotasConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public QuotaStore mongoQuotaStore(Mongo mongo) {
        return new MongoQuotaStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public QuotaStore inMemoryQuotaStore() {
        return new InMemoryQuotaStore();
    }

    @Bean
    public PlatformQuotaPort platformQuotaPort(PlatformPort platform) {
        return new PlatformQuotaAdapter(platform);
    }

    @Bean
    public QuotaQueries quotaQueries(QuotaStore quotas, PlatformQuotaPort platform, AccessKeyStore keys, ActionCatalogPort catalog, AccessGuard guard,
            IamSupport support, Clock clock) {
        return new QuotaQueries(quotas, platform, keys, catalog, guard, support, clock);
    }

    @Bean
    public QuotaRules quotaRules(QuotaRulesProperties properties) {
        return properties.rules();
    }

    @Bean
    public QuotaCommands quotaCommands(QuotaStore quotas, PlatformQuotaPort platform, QuotaQueries queries, QuotaRules rules, AccessGuard guard,
            IdentityEvents events, IamSupport support, Ids ids, Clock clock) {
        return new QuotaCommands(quotas, platform, queries, rules, guard, events, support, ids, clock);
    }
}
