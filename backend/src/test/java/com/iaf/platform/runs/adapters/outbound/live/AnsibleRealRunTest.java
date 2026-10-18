package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.adapters.artifacts.FilesystemArtifactStore;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.ports.GitRemotePort;
import com.iaf.platform.config.ArtifactsProperties;
import com.iaf.platform.config.PlaybooksProperties;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A real playbook on localhost, run only when {@code IAF_ANSIBLE_BIN} names a directory with
 * ansible-runner and ansible-playbook (a virtualenv's bin with ansible-core, ansible-runner and
 * the ansible.posix collection): {@code pip install ansible ansible-runner}.
 */
@EnabledIfEnvironmentVariable(named = "IAF_ANSIBLE_BIN", matches = ".+")
class AnsibleRealRunTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PLAYBOOK = """
            - name: smoke
              hosts: all
              gather_facts: false
              tasks:
                - name: say hello
                  ansible.builtin.debug: { msg: "hello {{ who }}" }
                - name: change something
                  ansible.builtin.command: echo hi
                  changed_when: true
                - name: publish stats
                  ansible.builtin.set_stats:
                    data: { rebootRequired: true, packages: [ { name: openssl } ] }
            """;

    @TempDir
    Path tmp;
    RunnersProperties props;
    AnsibleRunnerAutomation runner;
    String sha;
    final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Path bin = Path.of(System.getenv("IAF_ANSIBLE_BIN"));
        props = new RunnersProperties();
        props.setWorkDir(tmp.resolve("work").toString());
        props.getAnsible().setRunner(bin.resolve("ansible-runner").toString());
        props.getAnsible().setPlaybook(bin.resolve("ansible-playbook").toString());
        ArtifactsProperties store = new ArtifactsProperties();
        store.setDir(tmp.resolve("artifacts").toString());
        ArtifactStorePort artifacts = new FilesystemArtifactStore(store);
        sha = artifacts.put(Files.writeString(tmp.resolve("smoke.yml"), PLAYBOOK)).block().sha256();
        CredentialsPort vault = mock(CredentialsPort.class);
        when(vault.resolve(anyString())).thenReturn(Mono.empty());
        ProviderQueries providers = mock(ProviderQueries.class);
        when(providers.all()).thenReturn(Flux.empty());
        runner = new AnsibleRunnerAutomation(props, new PlaybooksProperties(), mock(GitRemotePort.class), artifacts, vault, providers);
    }

    JsonNode run() throws Exception {
        ObjectNode with = (ObjectNode) JSON.readTree("""
                {"playbook": {"upload": {"file": "smoke.yml", "sha256": "SHA"}},
                 "inventory": {"hosts": ["localhost"], "vars": {"ansible_connection": "local", "ansible_python_interpreter": "/usr/bin/python3"}},
                 "extraVars": {"who": "world"}}
                """.replace("SHA", sha));
        Step step = new Step("smoke", "Smoke", StepType.ANSIBLE, with, null, null, null, "5m", true, null, null, null, null, null, null, null);
        return runner.run(new RunRequest("job-real", 1, StepType.ANSIBLE, step, with, "dev", false, false), logged::add).block(Duration.ofMinutes(5));
    }

    @Test
    void aRealPlaybookRunsOnLocalhostThroughAnsibleRunner() throws Exception {
        JsonNode out = run();

        assertThat(out.path("changed").asInt()).isEqualTo(1);
        assertThat(out.path("failed").asInt()).isZero();
        assertThat(out.path("hosts").path("localhost").path("ok").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(out.path("rebootRequired").asBoolean()).isTrue();
        assertThat(out.path("packages").get(0).path("name").asText()).isEqualTo("openssl");
        assertThat(logged).contains("PLAY [smoke]", "TASK [change something]", "changed: [localhost]");
    }

    @Test
    void aRealPlaybookRunsThroughAnsiblePlaybookWithTheJsonCallback() throws Exception {
        props.getAnsible().setRunner(tmp.resolve("absent/ansible-runner").toString());

        JsonNode out = run();

        assertThat(out.path("changed").asInt()).isEqualTo(1);
        assertThat(out.path("rebootRequired").asBoolean()).isTrue();
        assertThat(logged).contains("TASK [change something]");
    }
}
