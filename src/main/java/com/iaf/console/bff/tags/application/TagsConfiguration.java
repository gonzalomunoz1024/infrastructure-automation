package com.iaf.console.bff.tags.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.resources.ports.outbound.StatePort;
import com.iaf.console.bff.tags.ports.outbound.AccountDirectoryPort;
import com.iaf.console.bff.types.application.Types;

/** Wires the tag guardrails over the identity service's account records (the HTTP adapter registers itself) and the Tag Editor. */
@Configuration(proxyBeanMethods = false)
public class TagsConfiguration {

    @Bean
    public TagPolicies tagPolicies(TagsProperties properties, AccountDirectoryPort accounts) {
        return new TagPolicies(properties, accounts);
    }

    @Bean
    public TagEditor tagEditor(Types types, Access access, Resources resources, StatePort state, TagPolicies policies, PlatformProperties platform) {
        return new TagEditor(types, access, resources, state, policies, platform);
    }
}
