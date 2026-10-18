package com.iaf.platform.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/** Everything under {@code platform:} in application.yml. */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    private Content content = new Content();
    private IdentityProps identity = new IdentityProps();
    private Execution execution = new Execution();
    private Temporal temporal = new Temporal();
    private Policy policy = new Policy();
    private Credentials credentials = new Credentials();
    private Assistant assistant = new Assistant();
    private Audit audit = new Audit();
    private Demo demo = new Demo();
    private Persistence persistence = new Persistence();

    @Data
    public static class Content {
        /** The content repo: workflows/, providers/, resource-types/ live under it. */
        private String dir = "..";
        private boolean git = false;
    }

    @Data
    public static class IdentityProps {
        private String mode = "dev";
        private String defaultUser = "gonzalo";
        private String oidcIssuer = "";
        private String oidcJwksUri = "";
        private String oidcAudience = "";
    }

    @Data
    public static class Execution {
        private String mode = "in-memory";
    }

    @Data
    public static class Temporal {
        private String target = "127.0.0.1:7233";
        private String namespace = "default";
        private String taskQueue = "platform-interpreter";
        /** How long a step's call (provider, automation, script) may go without a heartbeat before it fails as worker-lost; 0 = never. */
        private Duration heartbeatTimeout = Duration.ofSeconds(60);
        /** How often those calls heartbeat while they run; well below the timeout. */
        private Duration heartbeatInterval = Duration.ofSeconds(15);
        /** The Temporal web UI, for the "Open in Temporal" link of a job; blank = no link. */
        private String uiUrl = "";
    }

    @Data
    public static class Policy {
        private String mode = "mock";
        private String opaUrl = "http://localhost:8181/v1/data/platform";
    }

    @Data
    public static class Credentials {
        private String mode = "in-memory";
        private String vaultUrl = "http://localhost:8200";
        private String vaultMount = "secret";
        private String vaultToken = "";
    }

    @Data
    public static class Assistant {
        private String mode = "mock";
        private String devinUrl = "https://api.devin.ai/v3";
        private String devinOrg = "";
        private String devinApiKey = "";
        private String devinPlaybookId = "";
        /** The playbook behind "Ask Devin" in the Add provider wizard ({@code !provider-draft}). */
        private String devinProviderPlaybookId = "";
        private String platformTokenSecretId = "";
    }

    @Data
    public static class Audit {
        private int retentionYears = 7;
        private Duration sealEvery = Duration.ofHours(1);
        /** HMAC-SHA256 key for seals and export packs; blank = a random key for this run (logged by id only). */
        private String signingKey = "";
    }

    @Data
    public static class Demo {
        private boolean seedHistory = true;
    }

    /** The JDBC stores of the postgres profile. */
    @Data
    public static class Persistence {
        /** How long provider-call samples are kept; older ones are deleted. */
        private Duration telemetryRetention = Duration.ofDays(30);
    }
}
