package com.iaf.platform.common.adapters.credentials;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.content.ContentFiles.ContentFile;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The demo vault: every {@code vault:<path>} reference found in the sample providers and
 * workflows exists, with a type guessed from the provider's auth and the path, and a generated
 * value that never leaves the process. {@code store} upserts; {@code list} never returns values.
 */
@Slf4j
@Component
public class InMemoryCredentials implements CredentialsPort {

    static final String SEED_AUTHOR = "content";
    private static final Pattern REFERENCE = Pattern.compile("vault:([A-Za-z0-9_./-]+)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry(String name, String type, String value, Instant updatedAt, String by) {
        CredentialInfo info() {
            return new CredentialInfo(name, type, CredentialsPort.referenceOf(name), updatedAt);
        }
    }

    private final PlatformProperties props;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryCredentials(PlatformProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    public void seed() {
        String dir = props.getContent().getDir();
        Path root = dir == null || dir.isBlank() ? null : Path.of(dir).toAbsolutePath().normalize();
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        Map<String, String> found = new LinkedHashMap<>();
        List<ContentFile> files = ContentFiles.scan(root);
        for (ContentFile f : files) {
            if ("Provider".equals(f.kind())) {
                try {
                    JsonNode auth = WorkflowYaml.readTree(f.yaml()).path("spec").path("auth");
                    String credential = auth.path("credential").asText(null);
                    if (credential != null && credential.startsWith("vault:") && !credential.contains("{{")) {
                        found.put(credential.substring("vault:".length()), typeOf(credential, auth.path("type").asText("none")));
                    }
                } catch (IllegalArgumentException e) {
                    // a broken file is the checks' business
                }
            }
        }
        for (ContentFile f : files) {
            Matcher m = REFERENCE.matcher(f.yaml());
            while (m.find()) {
                String name = m.group(1);
                // vault:databases/{{ $input.name }} is resolved per job, not a credential to seed
                boolean template = f.yaml().startsWith("{{", m.end()) || name.endsWith("/");
                if (!template && !name.isBlank()) {
                    found.putIfAbsent(name, typeOf("vault:" + name, null));
                }
            }
        }
        Instant now = clock.instant();
        found.forEach((name, type) -> entries.putIfAbsent(name, new Entry(name, type, generate(type, name), now, SEED_AUTHOR)));
        log.info("seeded {} credentials from the content under {}", found.size(), root);
    }

    /** Guess the type: the path first (ssh, api-key, password, token), then the provider's auth type. */
    static String typeOf(String reference, String authType) {
        String path = reference.toLowerCase();
        if (path.contains("ssh")) {
            return "ssh-key";
        }
        if (path.contains("api-key") || path.contains("apikey") || path.contains("api_key")) {
            return "api-key";
        }
        if (path.contains("password") || path.contains("passwd")) {
            return "password";
        }
        if (path.contains("basic")) {
            return "basic";
        }
        if (path.endsWith("token") || path.contains("token")) {
            return "token";
        }
        if (authType != null) {
            switch (authType) {
                case "basic" -> {
                    return "basic";
                }
                case "header" -> {
                    return "api-key";
                }
                default -> {
                }
            }
        }
        return "token";
    }

    private static String generate(String type, String name) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        return switch (type) {
            case "basic" -> "svc-" + name.replaceAll("[^a-z0-9]+", "-") + ":" + secret;
            case "ssh-key" -> "-----BEGIN OPENSSH PRIVATE KEY-----\n" + secret + "\n-----END OPENSSH PRIVATE KEY-----";
            default -> secret;
        };
    }

    @Override
    public Mono<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Mono.empty();
        }
        String name = reference.startsWith("vault:") ? reference.substring("vault:".length()) : reference;
        Entry e = entries.get(name);
        return e == null ? Mono.empty() : Mono.just(e.value());
    }

    @Override
    public Mono<List<CredentialInfo>> list() {
        return Mono.fromSupplier(() -> entries.values().stream().sorted(Comparator.comparing(Entry::name)).map(Entry::info).toList());
    }

    @Override
    public Mono<CredentialInfo> store(String name, String type, String value, String by) {
        return Mono.fromSupplier(() -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("value is required");
            }
            String key = name.startsWith("vault:") ? name.substring("vault:".length()) : name;
            Entry e = new Entry(key, type == null || type.isBlank() ? typeOf(key, null) : type, value, clock.instant(), by);
            entries.put(key, e);
            return e.info();
        });
    }

    /** For tests and the wizard's seed check: whether a reference is known, without its value. */
    public boolean has(String reference) {
        String name = reference.startsWith("vault:") ? reference.substring("vault:".length()) : reference;
        return entries.containsKey(name);
    }
}
