package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Problem;

/**
 * The Workflow file's JSON Schema as the first check: the backend's copy is the package's file,
 * every sample and template matches it, and the shared message table under
 * packages/workflow-checks/fixtures/schema-messages.json is answered word for word as the
 * TypeScript twin answers it (test/workflow-schema.test.ts).
 */
class WorkflowSchemaTest {

    static final Path PACKAGE_SCHEMA = SampleContentTest.ROOT.resolve("packages/workflow-checks/schemas/workflow.schema.json");
    static final Path MESSAGES = WorkflowChecksTest.FIXTURES.resolve("schema-messages.json");
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theBackendCarriesThePackageSchemaByteForByte() throws IOException {
        try (InputStream in = WorkflowSchema.class.getResourceAsStream(WorkflowSchema.RESOURCE)) {
            assertThat(in).as("classpath " + WorkflowSchema.RESOURCE).isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .as("backend/src/main/resources" + WorkflowSchema.RESOURCE + " is a copy of " + PACKAGE_SCHEMA + ": copy the file again")
                    .isEqualTo(Files.readString(PACKAGE_SCHEMA));
        }
    }

    @TestFactory
    Stream<DynamicTest> theSharedMessageTable() throws IOException {
        JsonNode table = JSON.readTree(MESSAGES.toFile());
        CheckContext ctx = SampleContentTest.context();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : table.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                List<Map<String, String>> actual = WorkflowChecks.check(c.path("yaml").asText(), ctx).stream().map(WorkflowSchemaTest::asMap).toList();
                List<Map<String, String>> expected = new ArrayList<>();
                c.path("problems").forEach(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    p.properties().forEach(e -> m.put(e.getKey(), e.getValue().asText()));
                    expected.add(m);
                });
                assertThat(actual).isEqualTo(expected);
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(20);
        return tests.stream();
    }

    private static Map<String, String> asMap(Problem p) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("level", p.level().json());
        m.put("code", p.code());
        if (p.step() != null) {
            m.put("step", p.step());
        }
        if (p.field() != null) {
            m.put("field", p.field());
        }
        m.put("message", p.message());
        return m;
    }

    @Test
    void everySampleAndTemplateMatchesTheSchemaAsWrittenAndAsStored() {
        List<String> report = new ArrayList<>();
        List<ContentFiles.ContentFile> files = ContentFiles.ofKind(SampleContentTest.ROOT, "Workflow");
        assertThat(files).hasSizeGreaterThanOrEqualTo(9);
        for (var f : files) {
            WorkflowSchema.validate(WorkflowYaml.readTree(f.yaml())).forEach(p -> report.add(f.relative(SampleContentTest.ROOT) + ": " + p.message()));
            WorkflowSchema.validate(WorkflowYaml.toJson(WorkflowYaml.parse(f.yaml()))).forEach(p -> report.add(f.relative(SampleContentTest.ROOT) + " (stored): " + p.message()));
        }
        for (WorkflowTemplates.Template t : WorkflowTemplates.all()) {
            WorkflowSchema.validate(WorkflowYaml.readTree(t.yaml())).forEach(p -> report.add("template " + t.id() + ": " + p.message()));
        }
        assertThat(report).isEmpty();
    }

    @Test
    void theSchemaComesFirstAndTheParserSecond() {
        CheckContext ctx = CheckContext.EMPTY;
        // a step with two step keys matches the schema; the parser refuses it
        List<Problem> twoKeys = WorkflowChecks.check("""
                kind: Workflow
                metadata: { name: x }
                workflow:
                  do:
                    - a: { call: provider, run: {} }
                """, ctx);
        assertThat(twoKeys).extracting(Problem::code, Problem::message).containsExactly(tuple("yaml.invalid", "Step 'a' has two step keys: call and run"));
        // a file of another kind is named by the parser
        assertThat(WorkflowChecks.check("kind: Provider\nmetadata: { name: x }\nspec: {}\n", ctx))
                .extracting(Problem::code, Problem::message).containsExactly(tuple("yaml.invalid", "kind must be Workflow, was Provider"));
        // a mapping is what the schema judges; anything else is the parser's
        assertThat(WorkflowChecks.check("- a\n- b\n", ctx)).extracting(Problem::message).containsExactly("A workflow file is a YAML mapping");
    }

    @Test
    void theDesignersJsonIsJudgedAsTheYamlIs() throws IOException {
        ObjectNode doc = (ObjectNode) WorkflowYaml.readTree(Files.readString(WorkflowChecksTest.FIXTURES.resolve("bad-step-key.yaml")));
        List<Problem> problems = WorkflowChecks.check(doc, SampleContentTest.context());
        assertThat(problems).extracting(Problem::code, Problem::step, Problem::field)
                .containsExactly(tuple("schema.invalid", "getVm", "/workflow/do/0/getVm/loop"));
    }

    @Test
    void aProblemIsLocatedInTheInnermostStep() {
        assertThat(WorkflowSchema.stepOf("")).isNull();
        assertThat(WorkflowSchema.stepOf("/metadata/foo")).isNull();
        assertThat(WorkflowSchema.stepOf("/workflow/do/0")).isNull();
        assertThat(WorkflowSchema.stepOf("/workflow/do/0/getVm/loop")).isEqualTo("getVm");
        assertThat(WorkflowSchema.stepOf("/workflow/do/1/both/fork/branches/0/2/left/retry/maxAttempts")).isEqualTo("left");
        assertThat(WorkflowSchema.stepOf("/workflow/do/0/each/for/do/0/inner/wait")).isEqualTo("inner");
        assertThat(WorkflowSchema.stepOf("/workflow/do/0/guard/try/catch/do/1/report/notify")).isEqualTo("report");
        assertThat(WorkflowSchema.stepOf("/workflow/catch/do/0/cleanUp/call")).isEqualTo("cleanUp");
        assertThat(WorkflowSchema.stepOf("/workflow/do/0/make/compensate/0/undo/call")).isEqualTo("undo");
        assertThat(WorkflowSchema.stepOf("/workflow/do/0/pick/switch/0/case/do/0/a~1b/call")).isEqualTo("a/b");
    }

    @Test
    void theServedDocumentIsACopyOfTheSchema() {
        JsonNode served = WorkflowSchema.document();
        assertThat(served.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(served.path("title").asText()).isEqualTo("Workflow");
        ((ObjectNode) served).put("title", "changed");
        assertThat(WorkflowSchema.document().path("title").asText()).isEqualTo("Workflow");
    }
}
