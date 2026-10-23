package com.iaf.console.state.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.fake.PlatformFake;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;

/**
 * The platform this service reads: the real one (or a platform-fake-app) at {@code console.platform.base-url}
 * with the console-internal service account's token, or, under the {@code demo} profile, a fake
 * started inside this JVM on its own port and seeded with the demo accounts, whose internal token
 * the service takes from the seed.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfiguration.class);

    @Bean
    @Profile("!demo")
    public PlatformPort platformClient(StateProperties properties) {
        StateProperties.PlatformSettings p = properties.platform();
        PlatformClient.Builder builder = PlatformClient.builder().baseUrl(p.baseUrl()).responseTimeout(p.timeout()).userAgent("console-state-service");
        if (p.token() != null && !p.token().isBlank()) {
            builder.bearerToken(p.token().trim());
        } else if (p.devUser() != null && !p.devUser().isBlank()) {
            builder.devUser(p.devUser().trim());
        } else {
            log.warn("console.platform.token is empty: the platform will answer 401 to every call until it is set");
        }
        return builder.build();
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("demo")
    static class Demo {

        @Bean(destroyMethod = "close")
        public PlatformFake platformFake(StateProperties properties) {
            // the fake seeds its demo world on start; the seed decides its own approvals while the
            // jobs run, which can race under a very short step delay, so a failed seed is retried
            // on a fresh fake rather than failing the boot
            RuntimeException failure = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                PlatformFake fake = PlatformFake.builder()
                        .stepDelay(properties.demo().stepDelay())
                        .seed(properties.demo().seed())
                        .demo(true)
                        .build();
                try {
                    fake.start(properties.demo().platformPort());
                    log.info("demo: the fake platform listens on {} with accounts {} and {}", fake.baseUrl(), fake.demo().compute().accountId(),
                            fake.demo().data().accountId());
                    return fake;
                } catch (RuntimeException e) {
                    failure = e;
                    log.warn("demo: the fake platform's seed failed on attempt {}: {}", attempt, e.toString());
                    fake.close();
                }
            }
            throw new IllegalStateException("the fake platform could not seed its demo", failure);
        }

        @Bean
        public PlatformPort demoPlatformClient(PlatformFake fake, StateProperties properties) {
            String token = properties.platform().token() != null && !properties.platform().token().isBlank() ? properties.platform().token().trim()
                    : fake.demo().internalToken();
            return PlatformClient.builder().baseUrl(fake.baseUrl()).bearerToken(token).responseTimeout(properties.platform().timeout())
                    .userAgent("console-state-service").build();
        }
    }
}
