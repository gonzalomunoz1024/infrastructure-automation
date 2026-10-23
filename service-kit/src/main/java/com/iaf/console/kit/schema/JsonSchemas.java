package com.iaf.console.kit.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * JSON Schema validation (draft 2020-12, the drafts the platform's files use) that answers the
 * platform's problems: one {@code schema} problem per failing field, named by its JSON pointer.
 */
public final class JsonSchemas {

    /** The code of every schema problem. */
    public static final String CODE = "schema";

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final JsonSchema schema;

    private JsonSchemas(JsonSchema schema) {
        this.schema = schema;
    }

    /** A validator of a schema given as JSON. */
    public static JsonSchemas of(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            throw new IllegalArgumentException("a schema is required");
        }
        return new JsonSchemas(FACTORY.getSchema(schema));
    }

    /** The problems of an input, empty when it is valid. */
    public List<Problem> validate(JsonNode input) {
        Set<ValidationMessage> messages = schema.validate(input == null ? NullNode.getInstance() : input);
        List<Problem> problems = new ArrayList<>();
        for (ValidationMessage m : messages) {
            String field = m.getInstanceLocation() == null ? null : m.getInstanceLocation().toString();
            problems.add(Problem.error(CODE, field == null || field.isEmpty() ? null : field, m.getMessage()));
        }
        problems.sort((a, b) -> String.valueOf(a.field()).compareTo(String.valueOf(b.field())));
        return problems;
    }

    public boolean isValid(JsonNode input) {
        return validate(input).isEmpty();
    }

    /** Throws an {@link InvalidInputException} (a 400 with problems) when the input fails. */
    public void check(JsonNode input, String what) {
        List<Problem> problems = validate(input);
        if (!problems.isEmpty()) {
            throw new InvalidInputException(what + " does not match its schema", problems);
        }
    }
}
