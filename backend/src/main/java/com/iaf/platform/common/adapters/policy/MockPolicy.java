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
 * <p>The {@code context} of a {@link PolicyInput} may carry {@code approvalRequired} (the
 * workflow's policy block, as the runs slice sends it; {@code policy.approval.required} is read
 * too), {@code preview.steps[].effect} (the preview) and {@code destructive} (a shortcut the
 * caller already judged).</p>
 *
 * <p>Resource access (a {@code use} step, phase {@code RESOURCE_ACCESS}) reads the resource the
 * registry puts in {@code input} ({@code owner}, {@code visibility}): a resource whose visibility
 * is {@code public}, or that has no owner team, is any team member's; one whose visibility is
 * {@code team} or unset is its owner team's only; an admin may use any. Publishing stays open to
 * every team member.</p>
 */
// TODO: wire the firm's OPA engine: the opa profile selects OpaPolicy, whose package names and
// rule ids are Gonzalo's to provide; this mock stays the default for demos and tests.
@Component
public class MockPolicy implements PolicyPort {

    public static final String ENGINE = "mock";
    /** The visibility that opens a resource to every team. */
    public static final String PUBLIC = "public";

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
        if (phase == Phase.PUBLISH) {
            rules.add(new Rule("phase.publish", "pass", "Publishing is open to team members"));
            return new PolicyDecision("allow", rules, ENGINE);
        }
        if (phase == Phase.RESOURCE_ACCESS) {
            Rule owner = resourceOwner(who, input.input() == null ? MissingNode.getInstance() : input.input());
            rules.add(owner);
            return new PolicyDecision("deny".equals(owner.result()) ? "deny" : "allow", rules, ENGINE);
        }
        boolean policyApproval = context.path("approvalRequired").asBoolean(false)
                || context.path("policy").path("approval").path("required").asBoolean(false)
                || context.path("approval").path("required").asBoolean(false);
        rules.add(new Rule("workflow.approval", policyApproval ? "needs-approval" : "pass",
                policyApproval ? "The workflow's policy requires an approval" : "The workflow's policy requires no approval"));
        boolean destructive = context.path("destructive").asBoolean(false) || destructiveStep(context.path("preview").path("steps"));
        rules.add(new Rule("preview.destructive", destructive ? "needs-approval" : "pass",
                destructive ? "The preview has a destructive step" : "No destructive step in the preview"));
        return new PolicyDecision(policyApproval || destructive ? "needs-approval" : "allow", rules, ENGINE);
    }

    /**
     * The resource-access rule: a {@code public} resource or one without an owner team is any
     * team member's, a {@code team} resource (the default, also when the visibility is unset) is
     * its owner team's; an admin may use any.
     */
    static Rule resourceOwner(Identity who, JsonNode resource) {
        String owner = resource.path("owner").asText(null);
        String visibility = resource.path("visibility").asText(null);
        String what = resource.path("type").asText("resource") + " " + resource.path("id").asText("?");
        if (who.admin()) {
            return new Rule("resource.owner", "pass", who.name() + " is an admin");
        }
        if (owner == null || owner.isBlank()) {
            return new Rule("resource.owner", "pass", what + " has no owner team");
        }
        if (PUBLIC.equals(visibility)) {
            return new Rule("resource.owner", "pass", what + " is public");
        }
        if (who.teams() != null && who.teams().contains(owner)) {
            return new Rule("resource.owner", "pass", who.name() + " is in " + owner + ", which owns " + what);
        }
        return new Rule("resource.owner", "deny", what + " belongs to " + owner + " and is not public; " + who.name() + " acts for " + who.teams());
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
