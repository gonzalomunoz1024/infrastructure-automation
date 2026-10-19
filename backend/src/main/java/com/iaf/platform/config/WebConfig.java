package com.iaf.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * CORS for the console's dev server, so the frontend can call the API and WebSocket in
 * development: the origins of {@code platform.cors.allowed-origins} (Vite on ports 3000 and 5173
 * by default) may call; a call from any other origin is refused with a 403.
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    private final PlatformProperties props;

    public WebConfig(PlatformProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(props.getCors().getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
