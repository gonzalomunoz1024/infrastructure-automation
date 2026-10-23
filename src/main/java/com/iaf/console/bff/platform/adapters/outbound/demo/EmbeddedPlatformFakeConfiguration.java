package com.iaf.console.bff.platform.adapters.outbound.demo;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.platform.application.PlatformConfiguration;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.platform.domain.DemoPlatformSeed;
import com.iaf.console.bff.platform.domain.PlatformCredential;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.fake.PlatformFake;
import com.iaf.console.fake.demo.DemoSeed;

/**
 * The demo's platform: platform-fake in this JVM, listening on its own port (18090 by default, so
 * anything else that expects platform-fake-app finds it there), trusting the BFF's own keys for
 * the P-02 assertions, with the demo world seeded. Its service account tokens become the
 * credentials of the demo accounts.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "console.platform.fake", name = "enabled", havingValue = "true")
public class EmbeddedPlatformFakeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPlatformFakeConfiguration.class);

    @Bean(destroyMethod = "close")
    public PlatformFake platformFake(PlatformProperties properties, Trust trust) {
        PlatformFake fake = PlatformFake.builder()
                .stepDelay(properties.fake().stepDelay())
                .seed(properties.fake().seed())
                .defaultUser(null)
                .issuer(trust.issuer())
                .audience("platform")
                .trustedKeys(trust.jwks())
                .generateDemoKey(false)
                .demo(true)
                .build();
        int port = fake.start(properties.fake().port());
        log.info("embedded platform-fake listening on http://localhost:{} (demo accounts {} and {})", port, DemoSeed.ACCOUNT_COMPUTE, DemoSeed.ACCOUNT_DATA);
        return fake;
    }

    @Bean
    public PlatformConfiguration.EmbeddedPlatformUrl embeddedPlatformUrl(PlatformFake fake) {
        return fake::baseUrl;
    }

    @Bean
    public DemoPlatformSeed demoPlatformSeed(PlatformFake fake) {
        DemoSeed.Result demo = fake.demo();
        return new DemoPlatformSeed(List.of(
                new PlatformCredential(demo.compute().accountId(), demo.compute().serviceAccountId(), demo.compute().token()),
                new PlatformCredential(demo.data().accountId(), demo.data().serviceAccountId(), demo.data().token())),
                new PlatformCredential(null, demo.internalServiceAccountId(), demo.internalToken()));
    }
}
