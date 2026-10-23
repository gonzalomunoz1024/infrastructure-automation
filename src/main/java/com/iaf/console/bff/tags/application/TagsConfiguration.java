package com.iaf.console.bff.tags.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.tags.ports.outbound.AccountDirectoryPort;

/** Wires the tag guardrails over the identity service's account records (the HTTP adapter registers itself). */
@Configuration(proxyBeanMethods = false)
public class TagsConfiguration {

    @Bean
    public TagPolicies tagPolicies(TagsProperties properties, AccountDirectoryPort accounts) {
        return new TagPolicies(properties, accounts);
    }
}
