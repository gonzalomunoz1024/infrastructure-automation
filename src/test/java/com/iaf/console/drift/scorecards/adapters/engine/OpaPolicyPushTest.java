package com.iaf.console.drift.scorecards.adapters.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.drift.scorecards.adapters.content.RulePackLoader;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;

/** What the OPA adapter refuses to load, without an OPA. */
class OpaPolicyPushTest {

    private static final RulePacks PACKS = RulePackLoader.load(null);

    @Test
    void aRuleDeclaringAnotherPackageIsNeverPushed() {
        assertThat(OpaPolicyEngine.declaredPackage("# x\npackage console.scorecards.p.r\n\ndeny[m] { m := 1 }")).isEqualTo("console.scorecards.p.r");
        assertThat(OpaPolicyEngine.declaredPackage("deny := []")).isNull();
        Rule rule = PACKS.allRules().get(0);
        Rule hostile = new Rule(rule.pack(), rule.id(), rule.title(), rule.description(), rule.severity(), rule.types(), rule.when(), rule.check(),
                rule.message(), "package system.authz\nallow := true", rule.remediation(), rule.examples());
        RulePack first = PACKS.packs().get(0);
        RulePacks packs = RulePacks.of(List.of(new RulePack(first.name(), first.title(), first.description(), first.version(), first.owner(), List.of(hostile))));
        // nothing listens on port 9: a call would fail with a connection error, not the package check
        OpaPolicyEngine engine = new OpaPolicyEngine(WebClient.builder(), "http://127.0.0.1:9", Duration.ofSeconds(2), null);
        assertThatThrownBy(() -> engine.pushPolicies(packs).block(Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("system.authz");
    }

    @Test
    void everyBuiltInRuleDeclaresItsOwnPackage() {
        for (Rule rule : PACKS.allRules()) {
            assertThat(OpaPolicyEngine.declaredPackage(rule.rego())).as(rule.key().toString()).isEqualTo(rule.regoPackage());
        }
    }
}
