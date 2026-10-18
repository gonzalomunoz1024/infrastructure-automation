package com.iaf.platform.common.adapters.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort.Phase;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.common.ports.PolicyPort.Rule;

import reactor.test.StepVerifier;

class MockPolicyTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity NOBODY = new Identity("bot", "Bot", null, List.of(), List.of("user"), false, Identity.Kind.SERVICE);

    static PolicyInput input(Identity who, Phase phase, String context) throws Exception {
        return new PolicyInput(phase, who, "vm-restart", "1.2.0", JSON.readTree("{\"vmId\":\"vm-1\"}"), context == null ? null : JSON.readTree(context));
    }

    @Test
    void deniesARequesterWithoutATeam() throws Exception {
        PolicyDecision d = MockPolicy.decide(input(NOBODY, Phase.INVOKE, null));
        assertThat(d.decision()).isEqualTo("deny");
        assertThat(d.allowed()).isFalse();
        assertThat(d.engine()).isEqualTo("mock");
        assertThat(d.rules()).extracting(Rule::id).containsExactly("requester.team");
        assertThat(d.rules().get(0).result()).isEqualTo("deny");
    }

    @Test
    void allowsAPlainRun() throws Exception {
        PolicyDecision d = MockPolicy.decide(input(MATEO, Phase.INVOKE, "{}"));
        assertThat(d.decision()).isEqualTo("allow");
        assertThat(d.needsApproval()).isFalse();
        assertThat(d.rules()).extracting(Rule::id).containsExactly("requester.team", "workflow.approval", "preview.destructive");
        assertThat(d.rules()).extracting(Rule::result).containsOnly("pass");
    }

    @Test
    void needsApprovalWhenTheWorkflowPolicySaysSo() throws Exception {
        PolicyDecision d = MockPolicy.decide(input(MATEO, Phase.INVOKE, "{\"policy\":{\"approval\":{\"required\":true,\"group\":\"compute-approvers\"}}}"));
        assertThat(d.decision()).isEqualTo("needs-approval");
        assertThat(d.allowed()).isTrue();
        assertThat(d.rules().stream().filter(r -> r.id().equals("workflow.approval")).findFirst().orElseThrow().result()).isEqualTo("needs-approval");
    }

    @Test
    void needsApprovalWhenThePreviewHasADestructiveStep() throws Exception {
        PolicyDecision d = MockPolicy.decide(input(MATEO, Phase.STEP,
                "{\"preview\":{\"steps\":[{\"id\":\"get\",\"effect\":\"read\"},{\"id\":\"delete\",\"effect\":\"delete\"}]}}"));
        assertThat(d.decision()).isEqualTo("needs-approval");
        assertThat(d.rules().stream().filter(r -> r.id().equals("preview.destructive")).findFirst().orElseThrow().message()).contains("destructive");
        assertThat(MockPolicy.decide(input(MATEO, Phase.INVOKE, "{\"destructive\":true}")).decision()).isEqualTo("needs-approval");
    }

    @Test
    void publishingIsOpenToTeamMembers() throws Exception {
        assertThat(MockPolicy.decide(input(MATEO, Phase.PUBLISH, "{\"destructive\":true}")).decision()).isEqualTo("allow");
        assertThat(MockPolicy.decide(input(NOBODY, Phase.PUBLISH, null)).decision()).isEqualTo("deny");
    }

    static PolicyInput resource(Identity who, String owner, String visibility) throws Exception {
        String json = "{\"id\":\"image-1\",\"type\":\"image\",\"status\":\"ready\""
                + (owner == null ? "" : ",\"owner\":\"" + owner + "\"")
                + (visibility == null ? "" : ",\"visibility\":\"" + visibility + "\"") + "}";
        return new PolicyInput(Phase.RESOURCE_ACCESS, who, null, null, JSON.readTree(json), JSON.readTree("{\"phase\":\"resource-access\"}"));
    }

    @Test
    void aTeamResourceIsItsOwnerTeamsAPublicOrOwnerlessOneIsAnyones() throws Exception {
        Identity admin = new Identity("gonzalo", "Gonzalo", null, List.of("team-platform"), List.of("admin"), true, Identity.Kind.USER);
        PolicyDecision own = MockPolicy.decide(resource(MATEO, "team-compute", "team"));
        assertThat(own.decision()).isEqualTo("allow");
        assertThat(own.rules()).extracting(Rule::id).containsExactly("requester.team", "resource.owner");
        assertThat(own.rules().get(1).message()).contains("team-compute");
        PolicyDecision other = MockPolicy.decide(resource(MATEO, "team-platform", "team"));
        assertThat(other.decision()).isEqualTo("deny");
        assertThat(other.allowed()).isFalse();
        assertThat(other.rules().get(1).result()).isEqualTo("deny");
        assertThat(other.rules().get(1).message()).contains("image image-1").contains("team-platform").contains("[team-compute]");
        assertThat(MockPolicy.decide(resource(MATEO, "team-platform", null)).decision()).as("an unset visibility is team").isEqualTo("deny");
        assertThat(MockPolicy.decide(resource(MATEO, "team-platform", "public")).decision()).isEqualTo("allow");
        assertThat(MockPolicy.decide(resource(MATEO, null, null)).decision()).as("no owner team").isEqualTo("allow");
        assertThat(MockPolicy.decide(resource(admin, "team-compute", "team")).decision()).as("admins always").isEqualTo("allow");
        assertThat(MockPolicy.decide(resource(Identity.SYSTEM, "team-compute", "team")).decision()).isEqualTo("allow");
        assertThat(MockPolicy.decide(resource(NOBODY, "team-compute", "public")).decision()).as("a requester without a team acts for nobody").isEqualTo("deny");
        assertThat(MockPolicy.decide(new PolicyInput(Phase.RESOURCE_ACCESS, MATEO, null, null, null, null)).decision()).as("no resource at all: nothing to own").isEqualTo("allow");
    }

    @Test
    void adminsAndThePlatformNeedNoTeam() throws Exception {
        assertThat(MockPolicy.decide(input(Identity.SYSTEM, Phase.INVOKE, null)).decision()).isEqualTo("allow");
    }

    @Test
    void theReactiveFaceIsTheSameRule() throws Exception {
        MockPolicy policy = new MockPolicy();
        assertThat(policy.engine()).isEqualTo("mock");
        StepVerifier.create(policy.evaluate(input(MATEO, Phase.INVOKE, "{}")))
                .assertNext(d -> assertThat(d.decision()).isEqualTo("allow"))
                .verifyComplete();
    }
}
