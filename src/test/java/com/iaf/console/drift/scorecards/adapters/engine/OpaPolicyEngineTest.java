package com.iaf.console.drift.scorecards.adapters.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.scorecards.adapters.content.RulePackLoader;
import com.iaf.console.drift.scorecards.domain.Rule;
import com.iaf.console.drift.scorecards.domain.RulePack;
import com.iaf.console.drift.scorecards.domain.RulePacks;
import com.iaf.console.drift.scorecards.domain.Verdict;

/**
 * The OPA adapter against a real OPA ({@code OPA_BIN}, which CI installs; without it the test
 * skips): the packs' Rego is loaded through the Policy API, and every example of every rule gets
 * the verdict it expects, one call per pack as the evaluator makes it.
 */
class OpaPolicyEngineTest {

    private static Process opa;
    private static String baseUrl;
    private static final RulePacks PACKS = RulePackLoader.load(null);

    @BeforeAll
    static void start() throws Exception {
        String bin = System.getenv("OPA_BIN");
        assumeTrue(bin != null && Files.isExecutable(Path.of(bin)), "OPA_BIN names no OPA binary");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        opa = new ProcessBuilder(bin, "run", "--server", "--addr", "127.0.0.1:" + port, "--log-level", "error").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        HttpClient http = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health")).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    break;
                }
            } catch (IOException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
            }
            Thread.sleep(100);
        }
    }

    @AfterAll
    static void stop() {
        if (opa != null) {
            opa.destroy();
        }
    }

    @Test
    void everyExampleGetsItsVerdictFromOpa() {
        OpaPolicyEngine engine = new OpaPolicyEngine(WebClient.builder(), baseUrl, Duration.ofSeconds(10), null);
        engine.pushPolicies(PACKS).block(Duration.ofSeconds(30));
        int checked = 0;
        for (RulePack pack : PACKS.packs()) {
            for (Rule rule : pack.rules()) {
                for (Rule.Example example : rule.examples()) {
                    Map<String, Verdict> verdicts = engine.evaluate(pack, List.of(rule), (ObjectNode) example.input()).block(Duration.ofSeconds(10));
                    Verdict v = verdicts.get(rule.id());
                    assertThat(v).as("%s answered", rule.key()).isNotNull();
                    String got = !v.applies() ? "not-applicable" : v.passed() ? "pass" : "fail";
                    assertThat(got).as("%s on %s", rule.key(), example.input()).isEqualTo(example.expect());
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(30);
    }

    @Test
    void aRuleOpaDoesNotAnswerHasNoVerdict() {
        RulePack pack = PACKS.packs().get(0);
        JsonNode result = Json.MAPPER.valueToTree(Map.of(pack.rules().get(0).id().replace('-', '_'), Map.of("applies", true, "deny", List.of("no"))));
        Map<String, Verdict> verdicts = OpaPolicyEngine.verdicts(pack.rules(), result);
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.get(pack.rules().get(0).id())).isEqualTo(Verdict.fail("no"));
    }
}
