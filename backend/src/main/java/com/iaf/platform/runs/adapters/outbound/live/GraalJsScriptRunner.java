package com.iaf.platform.runs.adapters.outbound.live;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * Production {@code script} steps: run the JavaScript in a sandboxed GraalJS context (no host
 * access, no I/O, a CPU and memory limit, the step's timeout) with {@code input} bound and the
 * returned value converted back to JSON. The demo runner's {@code MiniScript} understands only
 * the subset the sample scripts use; real scripts need the real engine.
 *
 * <p>TODO: add {@code org.graalvm.polyglot:polyglot} + {@code js} to the build, create one
 * {@code Engine} per process and a {@code Context} per call with {@code allowHostAccess(NONE)};
 * until then the live profile fails script steps loudly.</p>
 */
@Component
@Profile("live")
@Order(0)
public class GraalJsScriptRunner implements AutomationRunnerPort {

    @Override
    public boolean supports(StepType type) {
        return type == StepType.SCRIPT;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        return Mono.error(new UnsupportedOperationException(
                "GraalJS is not wired yet (profile live): the script of step " + (request.step() == null ? "?" : request.step().id())
                        + " cannot run. Add the polyglot dependency and implement GraalJsScriptRunner."));
    }
}
