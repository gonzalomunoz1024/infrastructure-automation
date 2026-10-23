package com.iaf.console.bff.resourcegroups.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resourcegroups.ports.outbound.ResourceGroupStore;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.session.application.Sessions;
import com.iaf.console.bff.types.application.Types;

/** Wires the resource groups over their store (memory, or Mongo with the mongo profile) and the resource pages. */
@Configuration(proxyBeanMethods = false)
public class ResourceGroupsConfiguration {

    @Bean
    public ResourceGroups resourceGroups(ResourceGroupStore store, Types types, Access access, Sessions sessions, Resources resources,
            PlatformProperties platform, Clock clock) {
        return new ResourceGroups(store, types, access, sessions, resources, platform, clock);
    }
}
