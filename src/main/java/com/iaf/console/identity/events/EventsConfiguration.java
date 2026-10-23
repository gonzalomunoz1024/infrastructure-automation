package com.iaf.console.identity.events;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.identity.common.Ids;
import com.iaf.console.kit.outbox.OutboxPort;

/** Wires the event appender with every listener in the context. */
@Configuration(proxyBeanMethods = false)
public class EventsConfiguration {

    @Bean
    public IdentityEvents identityEvents(OutboxPort outbox, Ids ids, Clock clock, ObjectMapper json, ObjectProvider<IdentityChangeListener> listeners) {
        List<IdentityChangeListener> all = listeners.orderedStream().toList();
        return new IdentityEvents(outbox, ids, clock, json, all);
    }
}
