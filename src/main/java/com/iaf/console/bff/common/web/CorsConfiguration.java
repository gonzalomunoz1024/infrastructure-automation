package com.iaf.console.bff.common.web;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** CORS for a web app served from another origin (the Vite dev server proxies instead, and needs none). */
@Configuration(proxyBeanMethods = false)
public class CorsConfiguration {

    @Bean
    public CorsWebFilter corsWebFilter(WebProperties web) {
        org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOrigins(web.origins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-Csrf-Token", "Idempotency-Key", "If-Match", "If-None-Match", "Last-Event-ID", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("ETag", "Retry-After", "X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/console/**", config);
        source.registerCorsConfiguration("/auth/**", config);
        return new CorsWebFilter(source);
    }
}
