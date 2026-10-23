package com.iaf.console.fake.boot;

import java.text.ParseException;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.fake.PlatformFake;
import com.nimbusds.jose.jwk.JWKSet;

/** Embeds the fake in a Spring WebFlux application when {@code console.platform-fake.enabled} is true. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "console.platform-fake", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformFakeProperties.class)
public class PlatformFakeAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PlatformFake platformFake(PlatformFakeProperties properties) {
        PlatformFake.Builder builder = PlatformFake.builder()
                .stepDelay(properties.stepDelay())
                .defaultUser(properties.defaultUser() == null || properties.defaultUser().isBlank() ? null : properties.defaultUser())
                .issuer(properties.issuer())
                .audience(properties.audience())
                .seed(properties.seed())
                .demo(properties.demo())
                .generateDemoKey(properties.generateDemoKey());
        if (properties.jwks() != null && !properties.jwks().isBlank()) {
            try {
                builder.trustedKeys(JWKSet.parse(properties.jwks()));
            } catch (ParseException e) {
                throw new IllegalArgumentException("console.platform-fake.jwks is not a JWK set", e);
            }
        }
        return builder.build();
    }

    @Bean
    public RouterFunction<ServerResponse> platformFakeRoutes(PlatformFake fake) {
        return fake.routes();
    }
}
