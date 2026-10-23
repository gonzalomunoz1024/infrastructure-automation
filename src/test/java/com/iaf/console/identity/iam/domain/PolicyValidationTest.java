package com.iaf.console.identity.iam.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.identity.common.Names;

/** What a policy document must hold before it is written. */
class PolicyValidationTest {

    private static final ActionCatalog CATALOG = ActionCatalog.builder().service("compute").verb("compute", "restart").verb("compute", "delete").build();
    private static final String ALL = IamResources.everything("wf", "123456789012");

    @Test
    void acceptsAWellFormedPolicy() {
        List<Statement> statements = List.of(
                Statement.allowExcept("ops", List.of("compute:*"), List.of("compute:delete"), List.of(ALL)),
                new Statement("prod", Statement.Effect.deny, List.of("compute:restart"), null, List.of("wfrn:wf:compute:*:123456789012:vm/*"),
                        Map.of("tag.env", "prod", "region", List.of("global", "eu-west-1"), "requester.group", "ops")));
        Names.Check check = PolicyValidation.check("no-prod-restarts", statements, CATALOG);
        assertThat(check.ok()).as(check.problems().toString()).isTrue();
    }

    @Test
    void namesEveryProblem() {
        List<Statement> statements = List.of(
                new Statement("a", Statement.Effect.allow, List.of("compute:fly", "nonsense"), null, List.of("not-a-wfrn"), Map.of("weather", "sunny")),
                new Statement("a", Statement.Effect.deny, List.of("compute:get"), List.of("compute:list"), List.of(ALL), Map.of("tag.env", Map.of("x", 1))),
                new Statement("c", null, List.of(), null, List.of(), null));
        Names.Check check = PolicyValidation.check("Bad Name", statements, CATALOG);
        assertThat(check.ok()).isFalse();
        String problems = check.problems().toString();
        assertThat(problems).contains("name")
                .contains("statements[0].actions[0]").contains("unknown-action")
                .contains("statements[0].actions[1]")
                .contains("statements[0].resources[0]")
                .contains("statements[0].conditions.weather")
                .contains("statements[1].sid")
                .contains("statements[1].notActions")
                .contains("statements[1].conditions.tag.env")
                .contains("statements[2].effect")
                .contains("statements[2].actions")
                .contains("statements[2].resources");
    }

    @Test
    void needsStatements() {
        assertThat(PolicyValidation.check("empty", List.of(), CATALOG).problems().toString()).contains("at least one statement");
        assertThat(PolicyValidation.check("none", null, CATALOG).ok()).isFalse();
        assertThat(PolicyValidation.isPattern("wfrn:wf:iam::123456789012:user/*")).isTrue();
        assertThat(PolicyValidation.isPattern("wfrn:wf:*")).isTrue();
        assertThat(PolicyValidation.isPattern("arn:aws:iam::1:user/x")).isFalse();
        assertThat(PolicyValidation.isPattern(null)).isFalse();
    }

    @Test
    void catalogKnowsActions() {
        assertThat(CATALOG.knows("compute:get")).isTrue();
        assertThat(CATALOG.knows("compute:restart")).isTrue();
        assertThat(CATALOG.knows("compute:fly")).isFalse();
        assertThat(CATALOG.knows("storage:get")).isFalse();
        assertThat(CATALOG.knows("*:get")).isTrue();
        assertThat(CATALOG.knows("compute:*")).isTrue();
        assertThat(CATALOG.knows("iam:manage")).isTrue();
        assertThat(ActionCatalog.consoleOnly().services()).contains("iam", "account", "quota", "audit", "cost", "search", "access-request");
        assertThat(Actions.isValid("compute:get")).isTrue();
        assertThat(Actions.isValid("compute")).isFalse();
        assertThat(Actions.isValid("a:b:c")).isFalse();
        assertThat(Actions.isValid("Compute:Get")).isFalse();
    }
}
