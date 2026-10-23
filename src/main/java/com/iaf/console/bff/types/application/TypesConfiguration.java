package com.iaf.console.bff.types.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.platform.application.Regions;

/** Wires the descriptors. */
@Configuration(proxyBeanMethods = false)
public class TypesConfiguration {

    @Bean
    public Types types(PlatformClients clients, Regions regions, Access access, PlatformProperties platform, TypesProperties properties) {
        return new Types(clients, regions, access, platform, properties);
    }
}
