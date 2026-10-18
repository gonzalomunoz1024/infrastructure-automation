package com.iaf.platform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;

/** Who may invoke, edit, approve, and send a credential outward. */
class AccessRulesTest {

    static final Identity MATEO = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity LEA = new Identity("lea", "Lea", null, List.of("team-data", "team-platform", "data-approvers"), List.of("sre"), false, Identity.Kind.USER);
    static final Identity ROOT = new Identity("root", "Root", null, List.of(), List.of("admin"), true, Identity.Kind.USER);
    static final Identity GUEST = new Identity("guest", "Guest", null, List.of(), List.of(), false, Identity.Kind.USER);

    @Test
    void invokingNeedsOneOfTheRolesOrTeamsTheWorkflowListsUnlessItListsNone() {
        assertThat(AccessRules.canInvoke(MATEO, List.of("ops", "sre"))).isTrue();
        assertThat(AccessRules.canInvoke(MATEO, List.of("team-compute"))).isTrue();
        assertThat(AccessRules.canInvoke(MATEO, List.of("sre"))).isFalse();
        assertThat(AccessRules.canInvoke(GUEST, List.of())).isTrue();
        assertThat(AccessRules.canInvoke(GUEST, null)).isTrue();
        assertThat(AccessRules.canInvoke(ROOT, List.of("sre"))).isTrue();
        assertThatThrownBy(() -> AccessRules.requireInvoke(MATEO, List.of("sre"), "vm-restart"))
                .isInstanceOf(ForbiddenException.class).hasMessage("Mateo may not invoke vm-restart (needs one of [sre])");
    }

    @Test
    void editingBelongsToTheOwnerTeamAndApprovingToTheGroup() {
        assertThat(AccessRules.canEdit(MATEO, "team-compute")).isTrue();
        assertThat(AccessRules.canEdit(MATEO, "team-data")).isFalse();
        assertThat(AccessRules.canEdit(MATEO, null)).isTrue();
        assertThat(AccessRules.canEdit(ROOT, "team-data")).isTrue();
        assertThat(AccessRules.canSeeProvider(LEA, "team-platform")).isTrue();
        assertThat(AccessRules.canApprove(LEA, "data-approvers")).isTrue();
        assertThat(AccessRules.canApprove(LEA, "sre")).isTrue();
        assertThat(AccessRules.canApprove(MATEO, "data-approvers")).isFalse();
        assertThatThrownBy(() -> AccessRules.requireEdit(MATEO, "team-data", "provider mongodb"))
                .isInstanceOf(ForbiddenException.class).hasMessage("Mateo may not change provider mongodb (owned by team-data)");
        assertThatThrownBy(() -> AccessRules.requireApprove(MATEO, "data-approvers", "job_1"))
                .isInstanceOf(ForbiddenException.class).hasMessage("Mateo is not in approver group data-approvers for job_1");
    }

    @Test
    void aCredentialIsUsedByItsOwnerTeamByAnyoneWhenItHasNoneAndByAdmins() {
        assertThat(AccessRules.canUseCredential(MATEO, "team-compute")).isTrue();
        assertThat(AccessRules.canUseCredential(MATEO, "team-data")).isFalse();
        assertThat(AccessRules.canUseCredential(LEA, "team-platform")).isTrue();
        assertThat(AccessRules.canUseCredential(MATEO, null)).as("seeded, or kept in the vault without an owner").isTrue();
        assertThat(AccessRules.canUseCredential(MATEO, " ")).isTrue();
        assertThat(AccessRules.canUseCredential(GUEST, "team-compute")).isFalse();
        assertThat(AccessRules.canUseCredential(GUEST, null)).isTrue();
        assertThat(AccessRules.canUseCredential(ROOT, "team-data")).isTrue();
        assertThat(AccessRules.canUseCredential(Identity.SYSTEM, "team-data")).as("the platform's own runs").isTrue();
    }

    @Test
    void theRefusalNamesTheCredentialAndItsOwnerAndNothingElse() {
        assertThatCode(() -> AccessRules.requireUseCredential(LEA, "vault:db/password", "team-platform")).doesNotThrowAnyException();
        assertThatCode(() -> AccessRules.requireUseCredential(MATEO, "vault:shared/token", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessRules.requireUseCredential(MATEO, "vault:db/password", "team-data"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Mateo may not use credential vault:db/password (owned by team-data)");
    }
}
