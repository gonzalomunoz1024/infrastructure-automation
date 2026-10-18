package com.iaf.platform.workflows.adapters.outbound;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

/**
 * Under the {@code github} profile every change request store bean (in memory, or the database
 * one its own profile makes primary) is wrapped in {@link GitHubChangeRequests}. Wrapping, rather
 * than one more primary {@link ChangeRequestPort}, keeps the store's own profile in charge of where
 * change requests are kept, and the two profiles combine.
 */
@Configuration
@Profile("github")
public class GitHubChangeRequestsConfig {

    @Bean
    static BeanPostProcessor gitHubChangeRequests(ObjectProvider<GitHubPullRequests> github) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ChangeRequestPort store && !(bean instanceof GitHubChangeRequests)) {
                    return new GitHubChangeRequests(store, github::getObject);
                }
                return bean;
            }
        };
    }
}
