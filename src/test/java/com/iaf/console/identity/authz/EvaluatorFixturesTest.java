package com.iaf.console.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.identity.authz.domain.Check;
import com.iaf.console.identity.authz.domain.CompiledAccount;
import com.iaf.console.identity.authz.domain.Decision;
import com.iaf.console.identity.authz.domain.Evaluator;

/** The Java evaluator answers every fixture case as expected. */
class EvaluatorFixturesTest {

    private static final Fixtures.Loaded LOADED = Fixtures.load();

    static Stream<Arguments> cases() {
        return LOADED.cases().stream().map(c -> Arguments.of(Named.of(c.name(), c)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void decidesAsTheFixturesExpect(Fixtures.Case c) {
        assertThat(c.input().checks()).hasSameSizeAs(c.expect());
        for (int i = 0; i < c.input().checks().size(); i++) {
            Check check = c.input().checks().get(i);
            Decision decision = Evaluator.decide(LOADED.compiled(), c.input().principal(), check);
            JsonNode got = Fixtures.JSON.valueToTree(decision);
            Fixtures.assertMatches(c.name(), i, c.expect().get(i), got);
        }
        if (c.version() != null) {
            CompiledAccount account = LOADED.compiled().accounts().get(c.input().principal().accountId());
            assertThat(account).isNotNull();
            assertThat(account.version()).isEqualTo(c.version());
        }
    }
}
