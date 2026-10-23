package com.iaf.console.bff.preferences.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.preferences.ports.outbound.PreferencesStore;

/** Wires the preferences. */
@Configuration(proxyBeanMethods = false)
public class PreferencesConfiguration {

    @Bean
    public PreferencesService preferencesService(PreferencesStore store) {
        return new PreferencesService(store);
    }
}
