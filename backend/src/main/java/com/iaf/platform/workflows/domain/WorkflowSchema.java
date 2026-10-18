package com.iaf.platform.workflows.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.schema.Schemas;

/**
 * The JSON Schema of a Workflow file: {@code packages/workflow-checks/schemas/workflow.schema.json},
 * carried by the backend as the classpath resource {@value #RESOURCE} (a byte-identical copy,
 * which WorkflowSchemaTest keeps so). Both check twins judge a document against it before the
 * parser reads it, one {@code schema.invalid} problem per violation, located by a JSON pointer;
 * {@code GET /api/v1/schemas/workflow.json} serves it to editors.
 */
public final class WorkflowSchema {

    public static final String RESOURCE = "/schemas/workflow.schema.json";
    private static final JsonNode DOCUMENT = load();
    private static final Schemas.Compiled COMPILED = Schemas.compile(DOCUMENT);

    private WorkflowSchema() {
    }

    /** The schema document, a copy. */
    public static JsonNode document() {
        return DOCUMENT.deepCopy();
    }

    /**
     * One {@code schema.invalid} error per violation: the field is the JSON pointer of the value
     * ({@code /workflow/do/0/getVm/loop}), the step the innermost step the pointer is inside.
     */
    public static List<Problem> validate(JsonNode doc) {
        return COMPILED.violations(doc).stream()
                .map(v -> Problem.error("schema.invalid", stepOf(v.pointer()), v.pointer().isEmpty() ? null : v.pointer(), v.message()))
                .toList();
    }

    /**
     * The id of the innermost step a pointer leads into ({@code /workflow/do/0/getVm/retry} is
     * {@code getVm}, a step inside a fork branch, a loop or a catch likewise), or null.
     */
    static String stepOf(String pointer) {
        String[] seg = pointer.split("/", -1);
        String step = null;
        for (int i = 1; i < seg.length; i++) {
            if (("do".equals(seg[i]) || "compensate".equals(seg[i])) && i + 2 < seg.length && isIndex(seg[i + 1])) {
                step = unescape(seg[i + 2]);
            } else if ("branches".equals(seg[i]) && i + 3 < seg.length && isIndex(seg[i + 1]) && isIndex(seg[i + 2])) {
                step = unescape(seg[i + 3]);
            }
        }
        return step;
    }

    private static boolean isIndex(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static String unescape(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    private static JsonNode load() {
        try (InputStream in = WorkflowSchema.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the classpath has no " + RESOURCE);
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
