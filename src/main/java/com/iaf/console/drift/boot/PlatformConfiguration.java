package com.iaf.console.drift.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.fake.PlatformFake;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;

/**
 * The platform this service reads: the real one (or a platform-fake-app) at
 * {@code console.platform.base-url} with the console-internal service account's token (preset
 * {@code console-internal}: it reads and observes, it never starts a job), or, under the
 * {@code demo} profile, a fake started inside this JVM on its own port and seeded with the demo
 * accounts, whose internal token the service takes from the seed.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformConfiguration.class);
    static final String USER_AGENT = "console-drift-service";

    @Bean
    @Profile("!demo")
    public PlatformPort platformClient(DriftProperties properties) {
        DriftProperties.PlatformSettings p = properties.platform();
        PlatformClient.Builder builder = PlatformClient.builder().baseUrl(p.baseUrl()).responseTimeout(p.timeout()).userAgent(USER_AGENT);
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
        public PlatformFake platformFake(DriftProperties properties) {
            PlatformFake fake = PlatformFake.builder()
                    .stepDelay(properties.demo().stepDelay())
                    .seed(properties.demo().seed())
                    .demo(true)
                    .build();
            try {
                fake.start(properties.demo().platformPort());
            } catch (RuntimeException e) {
                fake.close();
                throw e;
            }
            int aligned = DemoWorld.alignSingleReads(fake);
            int sized = DemoWorld.sizeUnmanagedVms(fake);
            log.info("demo: the fake platform listens on {} with accounts {} and {}; {} provider object(s) read as recorded, {} unmanaged VM(s) sized",
                    fake.baseUrl(), fake.demo().compute().accountId(), fake.demo().data().accountId(), aligned, sized);
            return fake;
        }

        @Bean
        public PlatformPort demoPlatformClient(PlatformFake fake, DriftProperties properties) {
            String token = properties.platform().token() != null && !properties.platform().token().isBlank() ? properties.platform().token().trim()
                    : fake.demo().internalToken();
            return PlatformClient.builder().baseUrl(fake.baseUrl()).bearerToken(token).responseTimeout(properties.platform().timeout())
                    .userAgent(USER_AGENT).build();
        }
    }
}
