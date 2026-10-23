package com.iaf.console.kit.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

class JsonSchemasTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonSchemas schema() throws Exception {
        return JsonSchemas.of(JSON.readTree("""
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "required": ["name", "cpu"],
                  "properties": {
                    "name": { "type": "string", "pattern": "^[a-z][a-z0-9-]{2,30}$" },
                    "cpu": { "type": "integer", "minimum": 1, "maximum": 64 }
                  },
                  "additionalProperties": false
                }
                """));
    }

    @Test
    void acceptsWhatMatches() throws Exception {
        assertThat(schema().validate(JSON.readTree("{\"name\":\"web-01\",\"cpu\":2}"))).isEmpty();
        assertThat(schema().isValid(JSON.readTree("{\"name\":\"web-01\",\"cpu\":2}"))).isTrue();
    }

    @Test
    void namesEveryFailingField() throws Exception {
        List<Problem> problems = schema().validate(JSON.readTree("{\"name\":\"W\",\"cpu\":\"two\",\"extra\":1}"));
        assertThat(problems).isNotEmpty();
        assertThat(problems).allSatisfy(p -> {
            assertThat(p.code()).isEqualTo(JsonSchemas.CODE);
            assertThat(p.isError()).isTrue();
        });
        assertThat(problems).extracting(Problem::field).contains("$.name", "$.cpu");
        assertThat(schema().validate(JSON.readTree("{}"))).extracting(Problem::message).anySatisfy(m -> assertThat(m).contains("name"));
        assertThat(schema().isValid(null)).isFalse();
    }

    @Test
    void checkThrowsABadRequestWithProblems() throws Exception {
        JsonSchemas schema = schema();
        assertThatThrownBy(() -> schema.check(JSON.readTree("{\"name\":\"web-01\"}"), "the vm input"))
                .isInstanceOf(InvalidInputException.class)
                .hasMessageContaining("the vm input")
                .satisfies(e -> assertThat(((InvalidInputException) e).problems()).isNotEmpty());
        assertThatThrownBy(() -> JsonSchemas.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
