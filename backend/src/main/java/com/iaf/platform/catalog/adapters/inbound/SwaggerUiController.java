package com.iaf.platform.catalog.adapters.inbound;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Swagger UI over the generated OpenAPI document ({@code /api/v1/openapi.json}): the page lives
 * at {@value #PAGE} ({@value #PATH} redirects there), its script and styles come from the
 * swagger-ui webjar under {@code /webjars/swagger-ui/}. Every URL the page uses is relative, so
 * it works behind a path prefix too. A bearer token typed into the page goes with every request
 * it makes (the API needs one in oidc mode; the page itself does not).
 */
@RestController
public class SwaggerUiController {

    public static final String PATH = "/swagger-ui";
    public static final String PAGE = PATH + "/index.html";
    static final String RESOURCE = "swagger-ui/index.html";
    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final String page;

    public SwaggerUiController() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " is missing from the classpath", e);
        }
    }

    /** {@value #PATH} and {@value #PATH}{@code /} to the page, relative to where the request came in. */
    @GetMapping({PATH, PATH + "/"})
    public ResponseEntity<Void> redirect(ServerHttpRequest request) {
        String location = request.getPath().value().endsWith("/") ? "index.html" : "swagger-ui/index.html";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    @GetMapping(PAGE)
    public ResponseEntity<String> page() {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noCache()).body(page);
    }
}
