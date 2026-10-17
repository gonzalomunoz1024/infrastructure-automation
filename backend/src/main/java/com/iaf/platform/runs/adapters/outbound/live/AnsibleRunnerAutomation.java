package com.iaf.platform.runs.adapters.outbound.live;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * Production {@code call: ansible}: run the playbook with ansible-runner inside the execution
 * environment the step names ({@code executionEnvironment: ee-platform:2.17}), checking out
 * {@code playbook.git.repo} at {@code playbook.commit}, passing {@code extraVars}, {@code limit}
 * and the inventory, resolving {@code credential} through {@code CredentialsPort}, and mapping
 * the runner's stats to {@code { ok, changed, failed, rebootRequired }}.
 *
 * <p>TODO: wire ansible-runner (container runtime, EE registry, SSH key injection) and run it on
 * {@code Schedulers.boundedElastic()} with the step's timeout; until then the live profile fails
 * ansible steps loudly instead of pretending.</p>
 */
@Component
@Profile("live")
@Order(0)
public class AnsibleRunnerAutomation implements AutomationRunnerPort {

    @Override
    public boolean supports(StepType type) {
        return type == StepType.ANSIBLE;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        return Mono.error(new UnsupportedOperationException(
                "ansible-runner is not wired yet (profile live): playbook "
                        + (request.with() == null ? "?" : request.with().path("playbook").toString())
                        + " cannot run. Configure the execution environment and the container runtime, then implement AnsibleRunnerAutomation."));
    }
}
