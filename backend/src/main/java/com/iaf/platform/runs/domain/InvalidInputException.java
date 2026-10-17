package com.iaf.platform.runs.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;

/**
 * The input of a job (or the rows of a batch) failed schema validation. An
 * {@link IllegalArgumentException}, so the edge answers 400; the message carries the problems as
 * JSON and the fields let a gateway build {@code { error, problems }} or {@code { error, rows }}.
 */
public class InvalidInputException extends IllegalArgumentException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The problems of one batch row. */
    public record RowProblems(int index, List<Problem> problems) {
    }

    private final transient List<Problem> problems;
    private final transient List<RowProblems> rows;

    public InvalidInputException(String workflow, List<Problem> problems) {
        super("Invalid input for " + workflow + ": problems: " + json(MAPPER.valueToTree(problems)));
        this.problems = problems;
        this.rows = null;
    }

    public InvalidInputException(String workflow, List<RowProblems> rows, boolean batch) {
        super("Invalid rows for " + workflow + ": rows: " + json(rowsJson(rows)));
        this.problems = null;
        this.rows = rows;
    }

    public List<Problem> problems() {
        return problems;
    }

    public List<RowProblems> rows() {
        return rows;
    }

    public JsonNode rowsJson() {
        return rows == null ? null : rowsJson(rows);
    }

    private static JsonNode rowsJson(List<RowProblems> rows) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (RowProblems r : rows) {
            ObjectNode o = arr.addObject();
            o.put("index", r.index());
            o.set("problems", MAPPER.valueToTree(r.problems()));
        }
        return arr;
    }

    private static String json(JsonNode n) {
        return n == null ? "[]" : n.toString();
    }
}
