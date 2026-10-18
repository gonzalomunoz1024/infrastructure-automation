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
    private Github github = new Github();
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
        /** The {@code iss} the tokens carry; required under the oidc profile. */
        private String oidcIssuer = "";
        /** The issuer's JWKS endpoint; required under the oidc profile. */
        private String oidcJwksUri = "";
        /** The {@code aud} the tokens carry for this platform; required under the oidc profile. */
        private String oidcAudience = "";
        /** The claim that lists the caller's teams. */
        private String oidcTeamsClaim = "groups";
        /** The claim that lists the caller's roles. */
        private String oidcRolesClaim = "roles";
        /** The role (in the roles claim) that makes the caller a platform admin. */
        private String oidcAdminRole = "platform-admin";
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

    /** The {@code github} profile: change requests become pull requests on the content repository. */
    @Data
    public static class Github {
        /** The REST API: https://api.github.com, or https://<host>/api/v3 for GitHub Enterprise Server. */
        private String apiUrl = "https://api.github.com";
        /** The content repository, {@code owner/name}. */
        private String repo = "";
        /** The branch pull requests merge into. */
        private String baseBranch = "main";
        /** A {@code vault:} reference to the token that opens, merges and closes the pull requests. */
        private String token = "vault:github/platform-token";
        /** A {@code vault:} reference to the webhook's shared secret, which signs every delivery. */
        private String webhookSecret = "vault:github/webhook-secret";
        /** How the platform merges a pull request: merge, squash or rebase. */
        private String mergeMethod = "squash";
        /** How long one GitHub API call may take. */
        private Duration timeout = Duration.ofSeconds(20);
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
