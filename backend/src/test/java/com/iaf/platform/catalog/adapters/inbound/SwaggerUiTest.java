package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Swagger UI at /swagger-ui over /api/v1/openapi.json, its assets from the webjar. */
class SwaggerUiTest {

    static final URI PAGE = URI.create("http://platform.example.com" + SwaggerUiController.PAGE);
    static final Pattern REFERENCE = Pattern.compile("(?:src|href)=\"([^\"]+)\"|url: '([^']+)'");

    WebTestClient client = WebTestClient.bindToController(new SwaggerUiController()).build();

    String page() {
        return client.get().uri(SwaggerUiController.PAGE).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noCache())
                .expectBody(String.class).returnResult().getResponseBody();
    }

    /** Every URL the page loads, resolved against the page's own. */
    List<String> references() {
        List<String> out = new ArrayList<>();
        Matcher m = REFERENCE.matcher(page());
        while (m.find()) {
            String ref = m.group(1) != null ? m.group(1) : m.group(2);
            out.add(PAGE.resolve(ref).getPath());
        }
        return out;
    }

    @Test
    void swaggerUiRedirectsToThePage() {
        client.get().uri("/swagger-ui").exchange().expectStatus().isFound().expectHeader().location("swagger-ui/index.html");
        client.get().uri("/swagger-ui/").exchange().expectStatus().isFound().expectHeader().location("index.html");
        // both land on the page, wherever the platform is mounted
        assertThat(URI.create("http://h/swagger-ui").resolve("swagger-ui/index.html").getPath()).isEqualTo(SwaggerUiController.PAGE);
        assertThat(URI.create("http://h/swagger-ui/").resolve("index.html").getPath()).isEqualTo(SwaggerUiController.PAGE);
        assertThat(URI.create("http://h/platform/swagger-ui").resolve("swagger-ui/index.html").getPath()).isEqualTo("/platform" + SwaggerUiController.PAGE);
    }

    @Test
    void thePageShowsTheGeneratedDocumentAndUsesRelativeUrlsOnly() {
        String page = page();
        assertThat(page).contains("SwaggerUIBundle(").contains("requestInterceptor");
        assertThat(page).doesNotContain("src=\"/").doesNotContain("href=\"/").doesNotContain("http://").doesNotContain("https://");
        assertThat(references()).contains("/api/v1/openapi.json", "/webjars/swagger-ui/swagger-ui-bundle.js", "/webjars/swagger-ui/swagger-ui.css");
    }

    @Test
    void everyAssetThePageLoadsIsServedFromTheWebjarWithoutAVersionInItsPath() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class, HttpHandlerAutoConfiguration.class,
                        CodecsAutoConfiguration.class, JacksonAutoConfiguration.class))
                .withBean(SwaggerUiController.class)
                .run(ctx -> {
                    WebTestClient app = WebTestClient.bindToApplicationContext(ctx).build();
                    app.get().uri("/swagger-ui").exchange().expectStatus().isFound();
                    app.get().uri(SwaggerUiController.PAGE).exchange().expectStatus().isOk();
                    for (String path : references()) {
                        if (path.startsWith("/webjars/")) {
                            app.get().uri(path).exchange().expectStatus().isOk();
                        }
                    }
                    app.get().uri("/webjars/swagger-ui/swagger-ui.css").exchange().expectHeader().contentTypeCompatibleWith("text/css");
                });
    }
}
