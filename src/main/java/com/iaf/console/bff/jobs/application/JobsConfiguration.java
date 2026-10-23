package com.iaf.console.bff.jobs.application;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.types.application.Types;

/** Wires jobs, approvals, retries and the failure explainer. */
@Configuration(proxyBeanMethods = false)
public class JobsConfiguration {

    @Bean
    public Jobs jobs(PlatformClients clients, Access access, Sessions sessions, Trust trust, PlatformProperties platform, Approvals approvals,
            JobRetries retries) {
        return new Jobs(clients, access, sessions, trust, platform, approvals, retries);
    }

    /** The failure explainer, with the copilot's reading when a {@link FailureAdvisor} is wired, else rules alone. */
    @Bean
    public FailureExplainer failureExplainer(Jobs jobs, JobRetries retries, Access access, PlatformClients clients, ObjectProvider<FailureAdvisor> advisor,
            Clock clock) {
        return new FailureExplainer(jobs, retries, access, clients, advisor.getIfAvailable(() -> FailureAdvisor.NONE), clock);
    }

    @Bean
    public JobRetries jobRetries(Types types, PlatformProperties platform) {
        return new JobRetries(types, platform);
    }

    @Bean
    public Approvals approvals(PlatformClients clients, Access access, Trust trust, PlatformProperties platform) {
        return new Approvals(clients, access, trust, platform);
    }
}
