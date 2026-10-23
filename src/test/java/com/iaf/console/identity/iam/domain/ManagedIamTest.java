package com.iaf.console.identity.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.identity.accounts.domain.Account;
import com.iaf.console.identity.authz.application.Compiler;
import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.Compiled;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.domain.Decision;
import com.iaf.console.identity.authz.domain.Evaluator;
import com.iaf.console.identity.authz.domain.Principal;
import com.iaf.console.identity.principals.domain.Membership;
import com.iaf.console.identity.principals.domain.User;

/** The four managed roles and groups: valid policies, and the decisions they give once compiled. */
class ManagedIamTest {

    private static final String ACCOUNT = "123456789012";
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @Test
    void managedPoliciesAreValidAgainstTheConsoleCatalog() {
        List<ManagedIam.ManagedRole> roles = ManagedIam.roles("wf", ACCOUNT);
        assertThat(roles).extracting(ManagedIam.ManagedRole::name).containsExactly("viewer", "operator", "account-admin", "auditor");
        for (ManagedIam.ManagedRole role : roles) {
            assertThat(PolicyValidation.check(role.policyName(), role.statements(), ActionCatalog.consoleOnly()).ok()).as(role.name()).isTrue();
        }
        assertThat(ManagedIam.groups()).extracting(ManagedIam.ManagedGroup::name).containsExactly("owners", "members", "viewers", "auditors");
        assertThat(ManagedIam.groupFor("account-admin")).isEqualTo("owners");
        assertThat(ManagedIam.groupFor("operator")).isEqualTo("members");
        assertThat(ManagedIam.groupFor("custom")).isNull();
        assertThat(ManagedIam.canonicalRole("admin")).isEqualTo("account-admin");
        assertThat(ManagedIam.canonicalRole("viewer")).isEqualTo("viewer");
        assertThat(ManagedIam.ROLES).containsExactly("viewer", "auditor", "operator", "account-admin");
    }

    @Test
    void compiledManagedIamDecidesLikeTheContractSays() {
        Account account = Account.create(ACCOUNT, "Compute", "team-compute", "global", "sa-1", NOW);
        List<Policy> policies = new ArrayList<>();
        List<Role> roles = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        Map<String, String> roleIds = new java.util.HashMap<>();
        for (ManagedIam.ManagedRole managed : ManagedIam.roles("wf", ACCOUNT)) {
            Policy policy = Policy.create("pol_" + managed.name(), ACCOUNT, managed.policyName(), managed.policyDescription(), managed.statements(), true, NOW);
            policies.add(policy);
            Role role = Role.create("role_" + managed.name(), ACCOUNT, managed.name(), managed.description(), List.of(policy.policyId()), true, NOW);
            roles.add(role);
            roleIds.put(role.name(), role.roleId());
        }
        for (ManagedIam.ManagedGroup managed : ManagedIam.groups()) {
            groups.add(Group.create("grp_" + managed.name(), ACCOUNT, managed.name(), managed.description(), List.of(roleIds.get(managed.role())),
                    managed.owningTeamJoins() ? List.of("team-compute") : List.of(), true, NOW));
        }
        User owner = User.create("usr_owner", "owner@example.com", "Owner", null, null, NOW);
        User dev = User.create("usr_dev", "dev@example.com", "Dev", null, null, NOW);
        User viewer = User.create("usr_viewer", "viewer@example.com", "Viewer", null, null, NOW);
        User auditor = User.create("usr_auditor", "auditor@example.com", "Auditor", null, null, NOW);
        List<Membership> memberships = List.of(
                Membership.create(ACCOUNT, owner, List.of("grp_owners"), NOW),
                Membership.create(ACCOUNT, dev, List.of("grp_members"), NOW),
                Membership.create(ACCOUNT, viewer, List.of("grp_viewers"), NOW),
                Membership.create(ACCOUNT, auditor, List.of("grp_auditors"), NOW));
        CompiledAccount compiledAccount = Compiler.compile(account, 3, Map.of(owner.userId(), owner, dev.userId(), dev, viewer.userId(), viewer,
                auditor.userId(), auditor), memberships, groups, roles, policies);
        Compiled compiled = new Compiled(Map.of(ACCOUNT, compiledAccount), 9);

        assertThat(compiledAccount.version()).isEqualTo(3);
        assertThat(compiledAccount.users().get("usr_dev").groups()).containsExactly("members");
        assertThat(compiledAccount.groups().get("members").roles()).containsExactly("operator");
        assertThat(compiledAccount.roles().get("operator").policies()).containsExactly("pol_operator");
        assertThat(compiledAccount.policies().get("pol_operator").statements().get(0).notActions()).contains("*:delete", "iam:*");

        String vm = "wfrn:wf:compute:global:" + ACCOUNT + ":vm/vm-1";
        String users = "wfrn:wf:iam::" + ACCOUNT + ":user/*";
        assertThat(decide(compiled, "usr_owner", "compute:delete", vm).allowed()).isTrue();
        assertThat(decide(compiled, "usr_owner", "iam:create", users).role()).isEqualTo("account-admin");
        assertThat(decide(compiled, "usr_dev", "compute:restart", vm).allowed()).isTrue();
        Decision devDelete = decide(compiled, "usr_dev", "compute:delete", vm);
        assertThat(devDelete.allowed()).isFalse();
        assertThat(devDelete.requestable()).isTrue();
        assertThat(devDelete.suggestedRole()).isEqualTo("account-admin");
        assertThat(decide(compiled, "usr_dev", "iam:create", users).allowed()).isFalse();
        assertThat(decide(compiled, "usr_dev", "iam:list", users).allowed()).isTrue();
        assertThat(decide(compiled, "usr_viewer", "compute:get", vm).allowed()).isTrue();
        assertThat(decide(compiled, "usr_viewer", "quota:request-increase", "wfrn:wf:iam::" + ACCOUNT + ":quota/resources.vm").allowed()).isTrue();
        Decision viewerCreate = decide(compiled, "usr_viewer", "compute:create", "wfrn:wf:compute:global:" + ACCOUNT + ":vm/*");
        assertThat(viewerCreate.allowed()).isFalse();
        assertThat(viewerCreate.suggestedRole()).isEqualTo("operator");
        assertThat(decide(compiled, "usr_auditor", "audit:export", "wfrn:wf:audit:global:" + ACCOUNT + ":trail/*").allowed()).isTrue();
        assertThat(decide(compiled, "usr_auditor", "compute:restart", vm).allowed()).isFalse();

        // cost and budgets (decision 25), on the names the BFF decides them on
        String cost = "wfrn:wf:cost::" + ACCOUNT + ":*";
        String budgets = "wfrn:wf:budgets::" + ACCOUNT + ":budget/*";
        String budget = "wfrn:wf:budgets::" + ACCOUNT + ":budget/bud-01jbx9r2m4k9d3c8f5r6t1w0yf";
        assertThat(decide(compiled, "usr_viewer", "cost:list", cost).allowed()).isTrue();
        assertThat(decide(compiled, "usr_viewer", "budgets:list", budgets).allowed()).isTrue();
        assertThat(decide(compiled, "usr_viewer", "cost:export", cost).suggestedRole()).isEqualTo("auditor");
        assertThat(decide(compiled, "usr_dev", "budgets:get", budget).allowed()).isTrue();
        assertThat(decide(compiled, "usr_dev", "budgets:create", budgets).suggestedRole()).isEqualTo("account-admin");
        assertThat(decide(compiled, "usr_dev", "budgets:update", budget).suggestedRole()).isEqualTo("account-admin");
        assertThat(decide(compiled, "usr_dev", "budgets:delete", budget).allowed()).isFalse();
        assertThat(decide(compiled, "usr_dev", "cost:export", cost).suggestedRole()).isEqualTo("auditor");
        Decision export = decide(compiled, "usr_auditor", "cost:export", cost);
        assertThat(export.allowed()).isTrue();
        assertThat(export.statement()).isEqualTo("audit");
        assertThat(decide(compiled, "usr_auditor", "budgets:update", budget).allowed()).isFalse();
        assertThat(decide(compiled, "usr_owner", "budgets:delete", budget).allowed()).isTrue();
        assertThat(decide(compiled, "usr_owner", "cost:export", cost).allowed()).isTrue();

        // blueprints and stacks (decision 33): everyone reads, operators launch and resume, admins tear down
        String stacks = "wfrn:wf:blueprints:global:" + ACCOUNT + ":stack/*";
        String stack = "wfrn:wf:blueprints:global:" + ACCOUNT + ":stack/stack-01jbx9r2m4k9d3c8f5r6t1w0yh";
        assertThat(decide(compiled, "usr_viewer", "blueprints:list", stacks).allowed()).isTrue();
        assertThat(decide(compiled, "usr_auditor", "blueprints:get", stack).allowed()).isTrue();
        assertThat(decide(compiled, "usr_viewer", "blueprints:launch", stacks).suggestedRole()).isEqualTo("operator");
        assertThat(decide(compiled, "usr_dev", "blueprints:launch", stacks).allowed()).isTrue();
        assertThat(decide(compiled, "usr_dev", "blueprints:resume", stack).allowed()).isTrue();
        assertThat(decide(compiled, "usr_dev", "blueprints:teardown", stack).suggestedRole()).isEqualTo("account-admin");
        assertThat(decide(compiled, "usr_owner", "blueprints:teardown", stack).allowed()).isTrue();
        assertThat(decide(compiled, "usr_auditor", "blueprints:launch", stacks).allowed()).isFalse();

        assertThat(decide(compiled, "usr_nobody", "compute:get", vm).reason()).isEqualTo("not a member of the account");
        assertThat(Evaluator.decide(compiled, new Principal(null, ACCOUNT, null, "console-state-service"), new Check("compute:delete", vm, null)).allowed())
                .isTrue();
    }

    private static Decision decide(Compiled compiled, String userId, String action, String resource) {
        return Evaluator.decide(compiled, new Principal(userId, ACCOUNT, List.of(), null), new Check(action, resource, null));
    }
}
