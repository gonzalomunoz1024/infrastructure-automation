package com.iaf.console.kit.correlation;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

/**
 * Registers the correlation filter and teaches Reactor to copy the correlation id from its context
 * into the MDC wherever a chain runs, so plain log statements carry it.
 */
@Configuration(proxyBeanMethods = false)
public class CorrelationConfiguration {

    public CorrelationConfiguration() {
        registerMdcAccessor();
    }

    /** Idempotent: the accessor is registered once per JVM, and automatic propagation turned on. */
    public static void registerMdcAccessor() {
        ContextRegistry registry = ContextRegistry.getInstance();
        boolean present = registry.getThreadLocalAccessors().stream().anyMatch(a -> Correlation.KEY.equals(a.key()));
        if (!present) {
            registry.registerThreadLocalAccessor(Correlation.KEY, () -> MDC.get(Correlation.KEY), v -> MDC.put(Correlation.KEY, v),
                    () -> MDC.remove(Correlation.KEY));
        }
        Hooks.enableAutomaticContextPropagation();
    }

    @Bean
    public CorrelationWebFilter correlationWebFilter() {
        return new CorrelationWebFilter();
    }
}
