package com.iaf.console.bff.platform.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.bff.platform.ports.outbound.PlatformCredentialsPort;

/** Wires the platform clients and the regions cache. */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    @Bean
    public PlatformBaseUrl platformBaseUrl(PlatformProperties properties, ObjectProvider<EmbeddedPlatformUrl> embedded) {
        return new PlatformBaseUrl(() -> {
            EmbeddedPlatformUrl url = embedded.getIfAvailable();
            return url == null ? properties.baseUrl() : url.get();
        });
    }

    @Bean
    public PlatformClients platformClients(PlatformCredentialsPort credentials, PlatformBaseUrl baseUrl, PlatformProperties properties, WebClient.Builder builder) {
        return new PlatformClients(credentials, baseUrl, properties, builder);
    }

    @Bean
    public Regions regions(PlatformClients clients) {
        return new Regions(clients);
    }

    /** The URL of an embedded platform, published by the demo configuration that starts it. */
    @FunctionalInterface
    public interface EmbeddedPlatformUrl {
        String get();
    }
}
