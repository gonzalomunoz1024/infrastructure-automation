package com.iaf.console.state.stack.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** A launch's parameters against its blueprint: every value checked, the defaults filled in. */
public final class ParameterCheck {

    private ParameterCheck() {
    }

    /** The parameters with their defaults; a 400 listing every problem otherwise. */
    public static ObjectNode check(Blueprint blueprint, JsonNode given) {
        List<Problem> problems = new ArrayList<>();
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (given != null && !given.isNull() && !given.isObject()) {
            throw new InvalidInputException("parameters is an object", List.of(Problem.error("type", "/parameters", "an object of the blueprint's parameters")));
        }
        if (given != null && given.isObject()) {
            for (Iterator<String> it = given.fieldNames(); it.hasNext();) {
                String name = it.next();
                if (!blueprint.parameters().containsKey(name)) {
                    problems.add(Problem.error("parameter.unknown", "/parameters/" + name, "the blueprint has no parameter " + name));
                }
            }
        }
        for (Map.Entry<String, BlueprintParameter> e : blueprint.parameters().entrySet()) {
            String name = e.getKey();
            BlueprintParameter p = e.getValue();
            String field = "/parameters/" + name;
            JsonNode value = given == null ? null : given.get(name);
            if (value == null || value.isNull()) {
                if (p.required()) {
                    problems.add(Problem.error("required", field, p.title() + " is required"));
                    continue;
                }
                value = p.defaultValue();
            }
            String problem = problemOf(p, value);
            if (problem != null) {
                problems.add(Problem.error(problem.substring(0, problem.indexOf(':')), field, problem.substring(problem.indexOf(':') + 2)));
                continue;
            }
            out.set(name, value.deepCopy());
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the parameters do not fit the blueprint", problems);
        }
        return out;
    }

    /** {@code code: message} when the value does not fit the parameter, else null. */
    static String problemOf(BlueprintParameter p, JsonNode value) {
        switch (p.type()) {
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    return "type: a whole number";
                }
                long v = value.asLong();
                if (p.minimum() != null && v < p.minimum() || p.maximum() != null && v > p.maximum()) {
                    return "range: " + (p.minimum() == null ? "at most " + p.maximum()
                            : p.maximum() == null ? "at least " + p.minimum() : "from " + p.minimum() + " to " + p.maximum());
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    return "type: true or false";
                }
            }
            default -> {
                if (!value.isTextual()) {
                    return "type: text";
                }
                if (p.pattern() != null && !Pattern.compile(p.pattern()).matcher(value.asText()).matches()) {
                    return "pattern: does not match " + p.pattern();
                }
                if (value.asText().length() > 1024) {
                    return "length: at most 1024 characters";
                }
            }
        }
        if (p.enumValues() != null && p.enumValues().stream().noneMatch(v -> v.equals(value) || v.isNumber() && value.isNumber() && v.asLong() == value.asLong())) {
            return "enum: one of " + String.join(", ", p.enumValues().stream().map(JsonNode::asText).toList());
        }
        return null;
    }
}
