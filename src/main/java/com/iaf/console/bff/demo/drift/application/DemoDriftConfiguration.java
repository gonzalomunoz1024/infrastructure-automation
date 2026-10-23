package com.iaf.console.bff.demo.drift.application;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.iaf.console.bff.demo.directory.application.DemoDirectory;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;

/**
 * The drift service's demo stand-in, in the {@code demo} profile only, so the Drift, Monitoring,
 * shadow-infrastructure and rightsizing screens work with nothing else running. It verifies the
 * BFF's {@code X-On-Behalf-Of} assertions with the BFF's own keys and the platform audience, as the
 * platform would.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDriftConfiguration {

    @Bean
    public DemoDrift demoDrift(DemoStateStore store, DemoDirectory directory, Trust trust, TrustProperties trustProperties, Clock clock) {
        return new DemoDrift(store, directory, trust, trustProperties.platformAudience(), clock);
    }
}
