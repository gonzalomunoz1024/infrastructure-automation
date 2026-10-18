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
    private Providers providers = new Providers();
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
        /** The Devin organization ({@code org-...}): every call goes to {@code <devin-url>/organizations/<devin-org>}. */
        private String devinOrg = "";
        private String devinApiKey = "";
        private String devinPlaybookId = "";
        /** The playbook behind "Ask Devin" in the Add provider wizard ({@code !provider-draft}). */
        private String devinProviderPlaybookId = "";
        private String platformTokenSecretId = "";
        /** Repositories attached to every session as read-only context (the workflows repo), as Devin names them. */
        private java.util.List<String> devinRepos = new java.util.ArrayList<>();
        /** ACU ceiling per session; 0 sends none. */
        private int devinMaxAcuLimit = 10;
        /** How often a session is polled; kept within 10 to 30 seconds. */
        private Duration devinPollEvery = Duration.ofSeconds(15);
        private Duration devinTimeout = Duration.ofMinutes(30);
        /** The playbooks and their structured-output schemas; blank means {@code <content.dir>/devin}. */
        private String devinDir = "";
        /** Create or update the Devin playbooks from devin/*.md when the platform starts. */
        private boolean syncPlaybooksOnStartup = false;
        /** The platform API the agent calls back (registry, :validate, :dry-run); blank means http://localhost:<server.port>. */
        private String platformApiUrl = "";
        /** How many times the platform sends its own check results back to the agent before it settles a draft. */
        private int maxRounds = 3;
    }

    @Data
    public static class Providers {
        /** Read Git repositories on this machine (file: URLs and paths): tests and local demos only. */
        private boolean gitAllowLocal = false;
        /** How long one fetch of a spec, playbook or module repository may take. */
        private Duration gitTimeout = Duration.ofSeconds(60);
        /** Where registry modules ({@code namespace/name/provider}) of a Terraform import are looked up. */
        private String terraformRegistryUrl = "https://registry.terraform.io";
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
}
