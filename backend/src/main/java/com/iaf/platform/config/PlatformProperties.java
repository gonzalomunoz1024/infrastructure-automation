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
    }

    @Data
    public static class Demo {
        private boolean seedHistory = true;
    }
}
