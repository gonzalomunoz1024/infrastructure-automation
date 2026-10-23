package com.iaf.console.kit.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

import java.util.List;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationPredicate;

/**
 * Micrometer and OpenTelemetry settings every console service shares: the service and region as
 * common tags on every meter, and no observations (so no traces) of the actuator's own endpoints.
 * The OpenTelemetry bridge and the OTLP exporter come with the kit; Spring Boot wires them from
 * {@code management.tracing.*} and {@code management.otlp.*}.
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryConfiguration {

    @Bean
    public MeterFilter consoleCommonTags(Environment environment) {
        String service = environment.getProperty("spring.application.name", "console");
        String region = environment.getProperty("console.region", "global");
        return MeterFilter.commonTags(List.of(Tag.of("service", service), Tag.of("region", region)));
    }

    @Bean
    public ObservationPredicate noActuatorObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext c) {
                String path = c.getCarrier().getPath().value();
                return !path.startsWith("/actuator");
            }
            return true;
        };
    }
}
