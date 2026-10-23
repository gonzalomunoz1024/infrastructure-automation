package com.iaf.console.identity.common.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.identity.common.IdentityProperties;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;

/**
 * The one client this service talks to the platform with, as the console's admin service account
 * (P-03 needs one to create console service accounts) or, against the fake, as the dev user.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformClientConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformPort.class)
    public PlatformPort platformPort(IdentityProperties properties, WebClient.Builder webClient) {
        IdentityProperties.Platform platform = properties.platform();
        PlatformClient.Builder builder = PlatformClient.builder().baseUrl(platform.baseUrl()).webClientBuilder(webClient)
                .userAgent("console-identity-service");
        if (platform.adminToken() != null && !platform.adminToken().isBlank()) {
            builder.bearerToken(platform.adminToken());
        } else {
            builder.devUser(platform.devUser() == null || platform.devUser().isBlank() ? "gonzalo" : platform.devUser());
        }
        return builder.build();
    }
}
