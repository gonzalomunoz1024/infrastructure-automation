package com.iaf.console.identity.common;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The service's own settings, under {@code console.identity}. */
@ConfigurationProperties(prefix = "console.identity")
public record IdentityProperties(String partition, String region, Platform platform, Authz authz, Vault vault) {

    public IdentityProperties {
        partition = partition == null || partition.isBlank() ? "wf" : partition;
        region = region == null || region.isBlank() ? "global" : region;
        platform = platform == null ? new Platform(null, null, null, null) : platform;
        authz = authz == null ? new Authz(null, null, null) : authz;
        vault = vault == null ? new Vault(null, null, null, null) : vault;
    }

    /**
     * How the service reaches the platform: as an admin (P-03 needs one to create console service
     * accounts), with a bearer token in production or the dev header against the fake.
     */
    public record Platform(String baseUrl, String adminToken, String devUser, Duration catalogRefresh) {

        public Platform {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:18090" : baseUrl;
            catalogRefresh = catalogRefresh == null ? Duration.ofMinutes(5) : catalogRefresh;
        }
    }

    /** The authorization settings: the OPA endpoint of the {@code opa} profile and the compiled cache. */
    public record Authz(String opaUrl, Duration cacheTtl, List<String> readVerbs) {

        public Authz {
            opaUrl = opaUrl == null || opaUrl.isBlank() ? "http://localhost:8181" : opaUrl;
            cacheTtl = cacheTtl == null ? Duration.ofSeconds(2) : cacheTtl;
            readVerbs = readVerbs == null || readVerbs.isEmpty() ? List.of("get", "list", "observe", "history") : List.copyOf(readVerbs);
        }
    }

    /** The Vault KV v2 mount the platform service account tokens are written to, under the {@code vault} profile. */
    public record Vault(String baseUrl, String token, String mount, String pathPrefix) {

        public Vault {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8200" : baseUrl;
            mount = mount == null || mount.isBlank() ? "secret" : mount;
            pathPrefix = pathPrefix == null || pathPrefix.isBlank() ? "console/accounts" : pathPrefix;
        }
    }
}
