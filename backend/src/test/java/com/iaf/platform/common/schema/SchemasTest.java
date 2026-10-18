package com.iaf.platform.common.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Problem;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.resource.SchemaLoader;

/**
 * Nothing a schema names is fetched: a {@code $ref} out of the schema is refused before anything
 * compiles, and the validator's loaders never reach the built-in ones that would request an
 * http(s) IRI. Plus the meta-check that tells an author why a schema is not one.
 */
class SchemasTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String METADATA = "https://169.254.169.254/latest/meta-data";

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static JsonNode remoteSchema() {
        return json("{\"type\": \"object\", \"properties\": {\"vmId\": {\"$ref\": \"" + METADATA + "\"}}}");
    }

    @Test
    void aSchemaReferringToAnHttpUrlIsRefusedAtValidationAndNeverFetched() {
        SchemaLoader neverCalled = iri -> fail("the validator asked to load " + iri);
        List<Problem> problems = Schemas.validate(Schemas.factory(neverCalled), remoteSchema(), json("{\"vmId\": \"vm-1\"}"));
        assertThat(problems).extracting(Problem::code).containsExactly("schema.remoteRef");
        assertThat(problems.get(0).message()).contains(METADATA).contains("nothing is fetched");
        // the platform's own entry point answers the same, before compiling anything
        assertThat(Schemas.validate(remoteSchema(), json("{}"))).extracting(Problem::code).containsExactly("schema.remoteRef");
    }

    @Test
    void everyIriTheValidatorWouldLoadReachesTheGuardingLoaderNotTheNetwork() {
        // bypass the refusal: compiling the schema makes the validator resolve the reference
        List<String> asked = new ArrayList<>();
        SchemaLoader recording = iri -> {
            asked.add(iri.toString());
            throw new IllegalStateException("refused " + iri);
        };
        JsonSchemaFactory factory = Schemas.factory(recording);
        assertThatThrownBy(() -> factory.getSchema(remoteSchema()).initializeValidators()).hasMessageContaining("refused " + METADATA);
        assertThat(asked).containsOnly(METADATA);
    }

    @Test
    void theProductionLoaderRefusesARemoteIriAndStillServesTheBundledMetaSchemas() {
        assertThatThrownBy(() -> Schemas.compile(remoteSchema())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(METADATA);
        // the meta-schema itself loads (from the classpath) and a remote $schema is refused, not fetched
        assertThat(Schemas.metaViolations(json("{\"type\": \"object\", \"properties\": {\"a\": {\"type\": \"string\"}}}"))).isEmpty();
        assertThat(Schemas.metaViolations(json("{\"$schema\": \"https://evil.example/meta\", \"type\": \"object\"}")))
                .extracting(Schemas.Violation::message).containsExactly("Schema from 'https://evil.example/meta' is not allowed to be loaded.");
    }

    @Test
    void aReferenceOutOfTheSchemaIsFoundWhereverItIsButNotInData() {
        assertThat(Schemas.remoteRef(json("{\"properties\": {\"a\": {\"$ref\": \"#/$defs/a\"}}, \"$defs\": {\"a\": {\"type\": \"string\"}}}"))).isEmpty();
        assertThat(Schemas.remoteRef(json("{\"allOf\": [{\"$ref\": \"other.json\"}]}"))).contains(new Schemas.RemoteRef("/allOf/0/$ref", "other.json"));
        assertThat(Schemas.remoteRef(json("{\"properties\": {\"$ref\": {\"$dynamicRef\": \"https://example.com/s\"}}}")))
                .contains(new Schemas.RemoteRef("/properties/$ref/$dynamicRef", "https://example.com/s"));
        assertThat(Schemas.remoteRef(json("{\"enum\": [{\"$ref\": \"https://example.com/s\"}], \"default\": {\"$ref\": \"https://example.com/s\"}}"))).isEmpty();
    }

    @Test
    void theMetaCheckSaysWhyASchemaIsNotOne() {
        assertThat(Schemas.metaViolations(json("{\"type\": \"object\", \"required\": \"vmId\"}")))
                .containsExactly(new Schemas.Violation("/required", "string found, array expected"));
        assertThat(Schemas.metaViolations(json("{\"type\": \"strin\"}")))
                .containsExactly(new Schemas.Violation("/type", "does not have a value in the enumeration [\"array\", \"boolean\", \"integer\", \"null\", \"number\", \"object\", \"string\"]"));
        // a pattern the validator cannot compile, located at the pattern
        assertThat(Schemas.metaViolations(json("{\"type\": \"object\", \"properties\": {\"a\": {\"pattern\": \"([\"}}}")))
                .extracting(Schemas.Violation::pointer).containsExactly("/properties/a/pattern");
        // a reference into the schema that points at nothing
        assertThat(Schemas.metaViolations(json("{\"type\": \"object\", \"properties\": {\"a\": {\"$ref\": \"#/$defs/missing\"}}}")))
                .containsExactly(new Schemas.Violation("/properties/a/$ref", "Reference #/$defs/missing cannot be resolved"));
        // an older dialect is judged by its own meta-schema
        assertThat(Schemas.metaViolations(json("{\"$schema\": \"http://json-schema.org/draft-07/schema#\", \"type\": \"object\", \"properties\": {\"a\": {\"type\": \"array\", \"items\": [{\"type\": \"string\"}]}}}")))
                .isEmpty();
    }

    @Test
    void validationStillLocatesTheInputsViolations() {
        List<Problem> problems = Schemas.validate(json("{\"type\": \"object\", \"required\": [\"vmId\"], \"properties\": {\"vmId\": {\"type\": \"string\"}}}"), json("{\"vmId\": 5}"));
        assertThat(problems).extracting(Problem::code, Problem::field).containsExactly(tuple("schema", "vmId"));
    }
}
