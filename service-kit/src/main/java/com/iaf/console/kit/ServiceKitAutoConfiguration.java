package com.iaf.console.kit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.iaf.console.kit.correlation.CorrelationConfiguration;
import com.iaf.console.kit.errors.ProblemExceptionHandler;
import com.iaf.console.kit.idempotency.IdempotencyConfiguration;
import com.iaf.console.kit.mongo.MongoKitConfiguration;
import com.iaf.console.kit.outbox.OutboxConfiguration;
import com.iaf.console.kit.telemetry.TelemetryConfiguration;

/**
 * Everything the kit contributes to a Spring Boot service, picked up through the auto-configuration
 * imports file: the error mapping, the correlation filter, the outbox, the idempotent consumer, the
 * MongoDB handle under the {@code mongo} profile and the telemetry defaults.
 */
@AutoConfiguration
@Import({CorrelationConfiguration.class, OutboxConfiguration.class, IdempotencyConfiguration.class, MongoKitConfiguration.class,
        TelemetryConfiguration.class})
public class ServiceKitAutoConfiguration {

    @Bean
    public ProblemExceptionHandler problemExceptionHandler() {
        return new ProblemExceptionHandler();
    }
}
