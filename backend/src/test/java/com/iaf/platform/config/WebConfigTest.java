package com.iaf.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class WebConfigTest {

    @RestController
    static class Ping {
        @PostMapping("/api/v1/ping")
        String ping() {
            return "pong";
        }
    }

    static WebTestClient http(PlatformProperties props) {
        return WebTestClient.bindToController(new Ping()).corsMappings(new WebConfig(props)::addCorsMappings).build();
    }

    /** A POST to the backend on port 8080 from a page of {@code origin}, as a browser sends it through the dev server's proxy. */
    static WebTestClient.ResponseSpec postFrom(WebTestClient http, String origin) {
        return http.post().uri("http://localhost:8080/api/v1/ping").header(HttpHeaders.ORIGIN, origin).exchange();
    }

    @Test
    void aConfiguredOriginMayPostAndAnUnlistedOneIsRefused() {
        PlatformProperties props = new PlatformProperties();
        props.getCors().setAllowedOrigins(List.of("http://localhost:4173"));
        WebTestClient http = http(props);

        postFrom(http, "http://localhost:4173").expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4173");
        postFrom(http, "http://localhost:3000").expectStatus().isForbidden();
    }

    @Test
    void byDefaultTheViteDevServerMayPostAndAnotherPortIsRefused() {
        WebTestClient http = http(new PlatformProperties());

        postFrom(http, "http://localhost:3000").expectStatus().isOk();
        postFrom(http, "http://127.0.0.1:3000").expectStatus().isOk();
        postFrom(http, "http://localhost:5173").expectStatus().isOk();
        postFrom(http, "http://localhost:4173").expectStatus().isForbidden();
    }

    @Test
    void theOriginsBindFromACommaSeparatedList() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("platform.cors.allowed-origins", "http://localhost:4173, https://console.example")));
        PlatformProperties props = binder.bind("platform", Bindable.ofInstance(new PlatformProperties())).get();
        assertThat(props.getCors().getAllowedOrigins()).containsExactly("http://localhost:4173", "https://console.example");
    }
}
