package com.iaf.platform.common.ports;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Identity;

import reactor.core.publisher.Mono;

/**
 * The policy engine. The demo adapter is a mock with a few rules; the {@code opa} profile calls
 * the firm's OPA engine (TODO: the real policy package names are Gonzalo's to provide).
 */
public interface PolicyPort {

    /** When the engine is asked: before a run (invoke), before a publish, when a resource is used. */
    enum Phase {
        INVOKE, PUBLISH, RESOURCE_ACCESS, STEP
    }

    record PolicyInput(Phase phase, Identity who, String workflow, String version, JsonNode input, JsonNode context) {
    }

    record Rule(String id, String result, String message) {
    }

    /** {@code decision} is allow, deny or needs-approval. */
    record PolicyDecision(String decision, List<Rule> rules, String engine) {
        public boolean allowed() {
            return "allow".equals(decision) || "needs-approval".equals(decision);
        }

        public boolean needsApproval() {
            return "needs-approval".equals(decision);
        }

        public static PolicyDecision allow(String engine) {
            return new PolicyDecision("allow", List.of(), engine);
        }
    }

    Mono<PolicyDecision> evaluate(PolicyInput input);

    String engine();
}
