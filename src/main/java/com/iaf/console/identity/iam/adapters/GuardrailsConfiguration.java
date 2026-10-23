package com.iaf.console.identity.iam.adapters;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.iaf.console.identity.accounts.ports.outbound.AccountStore;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.identity.common.auth.AccessGuard;
import com.iaf.console.identity.events.IdentityEvents;
import com.iaf.console.identity.iam.adapters.memory.InMemoryGuardrailStore;
import com.iaf.console.identity.iam.adapters.mongo.MongoGuardrailStore;
import com.iaf.console.identity.iam.application.GuardrailCommands;
import com.iaf.console.identity.iam.application.GuardrailQueries;
import com.iaf.console.identity.iam.application.IamSupport;
import com.iaf.console.identity.iam.ports.outbound.ActionCatalogPort;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.kit.mongo.Mongo;

/** The organization's guardrails. */
@Configuration(proxyBeanMethods = false)
public class GuardrailsConfiguration {

    @Bean
    @Profile("mongo")
    @Primary
    public GuardrailStore mongoGuardrailStore(Mongo mongo) {
        return new MongoGuardrailStore(mongo);
    }

    @Bean
    @Profile("!mongo")
    public GuardrailStore inMemoryGuardrailStore() {
        return new InMemoryGuardrailStore();
    }

    @Bean
    public GuardrailCommands guardrailCommands(GuardrailStore guardrails, AccountStore accounts, ActionCatalogPort catalog, IdentityEvents events, Ids ids,
            Clock clock) {
        return new GuardrailCommands(guardrails, accounts, catalog, events, ids, clock);
    }

    @Bean
    public GuardrailQueries guardrailQueries(GuardrailStore guardrails, AccessGuard guard, IamSupport support) {
        return new GuardrailQueries(guardrails, guard, support);
    }
}
