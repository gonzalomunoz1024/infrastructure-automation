package com.iaf.platform.providers.adapters.outbound;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.ports.outbound.ModuleRegistryPort;
import com.iaf.platform.workflows.domain.Semver;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link ModuleRegistryPort} over HTTP, at {@code platform.providers.terraform-registry-url}: the
 * modules API is found through {@code /.well-known/terraform.json}, a version's code through its
 * {@code download} endpoint (the X-Terraform-Get header), and its inputs through the module
 * endpoint where the registry has one. A token goes to the registry only, and never into a
 * message or a log.
 */
@Slf4j
@Component
public class HttpModuleRegistry implements ModuleRegistryPort {

    static final Duration TIMEOUT = Duration.ofSeconds(20);
    static final String DEFAULT_MODULES_API = "/v1/modules/";
    /** The most of an answer read; a module's description is well under it. */
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    /** Newest first; a release before the pre-releases of the same version. */
    static final Comparator<String> NEWEST_FIRST = Comparator.<String, Semver>comparing(Semver::parse)
            .thenComparing(v -> !v.contains("-"))
            .reversed();

    private final URI registry;
    private final CredentialsPort credentials;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile URI modules;

    @Autowired
    public HttpModuleRegistry(PlatformProperties props, CredentialsPort credentials, ObjectMapper json) {
        this(props.getProviders().getTerraformRegistryUrl(), credentials, json);
    }

    public HttpModuleRegistry(String registryUrl, CredentialsPort credentials, ObjectMapper json) {
        String url = registryUrl == null || registryUrl.isBlank() ? "https://registry.terraform.io" : registryUrl.trim();
        this.registry = URI.create(url.endsWith("/") ? url : url + "/");
        this.credentials = credentials;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String host() {
        return registry.getPort() < 0 ? registry.getHost() : registry.getHost() + ":" + registry.getPort();
    }

    @Override
    public Mono<List<String>> versions(String module, String credential) {
        return token(credential).flatMap(token -> blocking(() -> listVersions(module, token)));
    }

    @Override
    public Mono<Location> locate(String module, String version, String credential) {
        return token(credential).flatMap(token -> blocking(() -> {
            URI download = modules(token).resolve(module + "/" + version + "/download");
            Reply response = send(download, token, "look up " + module + " " + version);
            if (response.statusCode() == 404) {
                List<String> known = listVersions(module, token);
                throw new IllegalArgumentException("No version " + version + " of " + module + " in " + host() + "; its versions are "
                        + String.join(", ", known.subList(0, Math.min(10, known.size()))) + (known.size() > 10 ? ", ..." : ""));
            }
            requireOk(response, "look up " + module + " " + version, !token.isEmpty());
            String location = response.headers().firstValue("X-Terraform-Get").orElse(null);
            if (location == null || location.isBlank()) {
                JsonNode body = parse(response.body());
                location = body == null ? null : body.path("location").asText(null);
            }
            if (location == null || location.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, host() + " did not say where " + module + " " + version + " is (no X-Terraform-Get)");
            }
            return new Location(module, version, absolute(download, location.trim()));
        }));
    }

    @Override
    public Mono<JsonNode> details(String module, String version, String credential) {
        return token(credential).flatMap(token -> blocking(() -> {
            URI uri = modules(token).resolve(module + "/" + version);
            Reply response = send(uri, token, "describe " + module + " " + version);
            if (response.statusCode() == 404) {
                return null;
            }
            requireOk(response, "describe " + module + " " + version, !token.isEmpty());
            JsonNode body = parse(response.body());
            return body != null && body.path("root").isObject() ? body : null;
        }));
    }

    private List<String> listVersions(String module, String token) throws IOException, InterruptedException {
        URI uri = modules(token).resolve(module + "/versions");
        Reply response = send(uri, token, "list the versions of " + module);
        if (response.statusCode() == 404) {
            throw new IllegalArgumentException("No module " + module + " in " + host());
        }
        requireOk(response, "list the versions of " + module, !token.isEmpty());
        JsonNode body = parse(response.body());
        List<String> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode m : body.path("modules")) {
                for (JsonNode v : m.path("versions")) {
                    if (v.path("version").isTextual()) {
                        out.add(v.path("version").asText());
                    }
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No module " + module + " in " + host());
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /** The modules API, from the registry's service discovery (cached once found). */
    private URI modules(String token) throws IOException, InterruptedException {
        URI known = modules;
        if (known != null) {
            return known;
        }
        URI discovery = registry.resolve(".well-known/terraform.json");
        Reply response = send(discovery, token, "discover the modules API");
        String path = DEFAULT_MODULES_API;
        if (response.statusCode() / 100 == 2) {
            JsonNode body = parse(response.body());
            if (body != null && body.path("modules.v1").isTextual()) {
                path = body.path("modules.v1").asText();
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, host() + " is not a Terraform module registry (no modules.v1 in /.well-known/terraform.json)");
            }
        } else if (response.statusCode() != 404) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not discover the modules API of " + host() + ": " + response.statusCode());
        }
        URI api = registry.resolve(path.endsWith("/") ? path : path + "/");
        modules = api;
        return api;
    }

    /** An answer of the registry, its body read up to {@link #MAX_BODY_BYTES}. */
    private record Reply(int statusCode, HttpHeaders headers, String body) {
    }

    private Reply send(URI uri, String token, String what) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "application/json").GET();
        if (!token.isEmpty() && sameRegistry(uri)) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, host() + " answered more than " + (MAX_BODY_BYTES >> 20) + " MB to " + what);
                }
                return new Reply(response.statusCode(), response.headers(), new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not " + what + " at " + host() + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** The token only ever goes to the registry's own host. */
    private boolean sameRegistry(URI uri) {
        return registry.getScheme().equalsIgnoreCase(uri.getScheme()) && registry.getHost().equalsIgnoreCase(uri.getHost())
                && registry.getPort() == uri.getPort();
    }

    private void requireOk(Reply response, String what, boolean withToken) {
        int status = response.statusCode();
        if (status / 100 == 2) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new IllegalArgumentException(host() + " refused to " + what + (withToken
                    ? ": the registry token was not accepted"
                    : ": the module is private; give registry.credential, a vault: reference to a registry token"));
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not " + what + " at " + host() + ": " + status);
    }

    /** X-Terraform-Get may be relative to the download URL; a getter address ({@code git::...}) is kept as it is. */
    static String absolute(URI download, String location) {
        if (location.contains("::") || SCHEME.matcher(location).find() || location.matches("^(github\\.com|bitbucket\\.org)/.*")) {
            return location;
        }
        return download.resolve(location).toString();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private Mono<String> token(String credential) {
        if (credential == null || credential.isBlank()) {
            return Mono.just("");
        }
        String ref = credential.trim();
        return credentials.resolve(ref).switchIfEmpty(Mono.error(() -> new IllegalArgumentException("No secret at " + ref)));
    }

    private static <T> Mono<T> blocking(Call<T> call) {
        return Mono.fromCallable(() -> {
            try {
                return call.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Interrupted while calling the module registry");
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @FunctionalInterface
    private interface Call<T> {
        T call() throws IOException, InterruptedException;
    }
}
