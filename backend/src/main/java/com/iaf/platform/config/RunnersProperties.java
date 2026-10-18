package com.iaf.platform.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import lombok.Data;

/**
 * {@code platform.runners}: the live automation runners (profile {@code live}). Every executable
 * is an absolute path or a name looked up on {@code path}, then on the platform's own PATH; the
 * processes themselves get a minimal environment, never the platform's own. A step that names no
 * timeout gets the engine's (10 minutes).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.runners")
public class RunnersProperties {

    /** Per-step working directories (removed after the step) and the runner caches live here. */
    private String workDir = System.getProperty("java.io.tmpdir") + "/iaf-platform/runners";
    /** {@code docker} or {@code podman} runs every step in a container; blank runs on the host. */
    private String containerRuntime = "";
    /** How often a running step reports that it is alive, between its own progress events. */
    private Duration heartbeat = Duration.ofSeconds(10);
    /** The PATH of processes run on the host, after the directory of the executable itself. */
    private String path = "/usr/local/bin:/usr/bin:/bin";
    /** The platform's environment variables ansible and terraform also get (proxies, CA bundles, locale); never secrets. */
    private List<String> passEnv = new ArrayList<>(List.of("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy",
            "no_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "LANG", "TZ"));
    private Ansible ansible = new Ansible();
    private Terraform terraform = new Terraform();
    private Script script = new Script();
    private Http http = new Http();

    @Data
    public static class Ansible {
        /** ansible-runner; when it is not installed the runner falls back to {@code playbook}. */
        private String runner = "ansible-runner";
        private String playbook = "ansible-playbook";
        private String galaxy = "ansible-galaxy";
        /** The stdout callback of the ansible-playbook fallback. */
        private String jsonCallback = "ansible.posix.json";
        /** The execution environment of a step and provider that name none (container runtime only). */
        private String defaultImage = "";
        /** The container network; blank keeps the runtime's default (playbooks reach their hosts over it). */
        private String network = "";
        /** A file or directory (inventory plugin configs, scripts) copied in for steps that say {@code inventory: dynamic}. */
        private String dynamicInventory = "";
        /** Extra environment of every run, never secrets: ANSIBLE_HOST_KEY_CHECKING, ANSIBLE_FORKS. */
        private Map<String, String> env = new LinkedHashMap<>();
    }

    @Data
    public static class Terraform {
        private String binary = "terraform";
        /** The image of terraform when a container runtime is set; its entrypoint is terraform. */
        private String image = "hashicorp/terraform:1.9.8";
        /** Shared provider plugin cache; blank is {@code <work-dir>/terraform/plugin-cache}. */
        private String pluginCacheDir = "";
        /** Blank keeps state on local disk under {@code <work-dir>/terraform/state}; otherwise a backend type (s3, gcs, pg, ...). */
        private String backend = "";
        /** {@code -backend-config} pairs of that backend, never secrets (those come from the step's credential). */
        private Map<String, String> backendConfig = new LinkedHashMap<>();
    }

    @Data
    public static class Script {
        /** The interpreter of each language on the host. */
        private Map<String, String> commands = new LinkedHashMap<>(Map.of(
                "python", "python3", "bash", "bash", "javascript", "node", "go", "go"));
        /** The image of each language when a container runtime is set. */
        private Map<String, String> images = new LinkedHashMap<>(Map.of(
                "python", "python:3.12-slim", "bash", "bash:5.2", "javascript", "node:22-alpine", "go", "golang:1.24-alpine"));
        /** Container limits. */
        private String memory = "256m";
        private String cpus = "0.5";
        /** The Go build cache shared by script runs on the host; blank is {@code <work-dir>/script/go-cache}. */
        private String goCache = "";
        /** The most a script may print on stdout. */
        private DataSize maxOutput = DataSize.ofMegabytes(1);
    }

    @Data
    public static class Http {
        /** The timeout of an http step that names none. */
        private Duration timeout = Duration.ofSeconds(30);
        private DataSize maxBody = DataSize.ofMegabytes(10);
    }
}
