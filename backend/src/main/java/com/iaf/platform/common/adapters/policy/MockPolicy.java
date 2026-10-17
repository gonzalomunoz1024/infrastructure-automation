package com.iaf.platform.common.adapters.policy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort;

import reactor.core.publisher.Mono;

/**
 * The demo policy engine: a few deterministic rules so previews, approvals and the audit trail
 * show a real decision. Deny when the requester belongs to no team; needs-approval when the
 * workflow's policy requires it or the preview has a destructive step; allow otherwise.
 *
 * <p>The {@code context} of a {@link PolicyInput} may carry {@code policy.approval.required}
 * (the definition's policy block), {@code preview.steps[].effect} (the preview) and
 * {@code destructive} (a shortcut the caller already judged).</p>
 */
// TODO: wire the firm's OPA engine: the opa profile selects OpaPolicy, whose package names and
// rule ids are Gonzalo's to provide; this mock stays the default for demos and tests.
@Component
public class MockPolicy implements PolicyPort {

    public static final String ENGINE = "mock";

    @Override
    public Mono<PolicyDecision> evaluate(PolicyInput input) {
        return Mono.fromCallable(() -> decide(input));
    }

    @Override
    public String engine() {
        return ENGINE;
    }

    /** Pure, so tests can judge it without Reactor. */
    public static PolicyDecision decide(PolicyInput input) {
        List<Rule> rules = new ArrayList<>();
        Identity who = input == null ? null : input.who();
        boolean noTeam = who == null || (!who.admin() && (who.teams() == null || who.teams().isEmpty()));
        rules.add(new Rule("requester.team", noTeam ? "deny" : "pass",
                noTeam ? "The requester belongs to no team; every run is made on behalf of a team" : who.name() + " acts for " + who.teams()));
        if (noTeam) {
            return new PolicyDecision("deny", rules, ENGINE);
        }
        JsonNode context = input.context() == null ? MissingNode.getInstance() : input.context();
        Phase phase = input.phase() == null ? Phase.INVOKE : input.phase();
        if (phase == Phase.PUBLISH || phase == Phase.RESOURCE_ACCESS) {
            rules.add(new Rule("phase." + phase.name().toLowerCase(), "pass", "Publishing and resource access are open to team members"));
            return new PolicyDecision("allow", rules, ENGINE);
        }
        boolean policyApproval = context.path("policy").path("approval").path("required").asBoolean(false)
                || context.path("approval").path("required").asBoolean(false);
        rules.add(new Rule("workflow.approval", policyApproval ? "needs-approval" : "pass",
                policyApproval ? "The workflow's policy requires an approval" : "The workflow's policy requires no approval"));
        boolean destructive = context.path("destructive").asBoolean(false) || destructiveStep(context.path("preview").path("steps"));
        rules.add(new Rule("preview.destructive", destructive ? "needs-approval" : "pass",
                destructive ? "The preview has a destructive step" : "No destructive step in the preview"));
        return new PolicyDecision(policyApproval || destructive ? "needs-approval" : "allow", rules, ENGINE);
    }

    private static boolean destructiveStep(JsonNode steps) {
        if (steps == null || !steps.isArray()) {
            return false;
        }
        for (JsonNode s : steps) {
            if (Effect.of(s.path("effect").asText(null)).destructive()) {
                return true;
            }
        }
        return false;
    }
}
