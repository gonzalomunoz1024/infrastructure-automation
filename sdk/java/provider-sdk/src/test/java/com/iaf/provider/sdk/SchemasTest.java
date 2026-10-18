package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class SchemasTest {

    enum Size {
        SMALL, LARGE
    }

    record Disk(int sizeGb, boolean ssd) {
    }

    record Request(
            @Field(description = "Where it goes") String site,
            Optional<String> note,
            @Field(optional = true) Integer count,
            Size size,
            List<Disk> disks,
            Map<String, String> labels,
            Instant at,
            LocalDate day,
            UUID ref,
            double ratio,
            JsonNode extra,
            @JsonProperty("owner_team") String owner) {
    }

    record Loop(String name, Loop next) {
    }

    static final class NotARecord {
    }

    @Test
    void aRecordIsAnObjectWhoseComponentsAreRequiredUnlessOptional() {
        ObjectNode schema = Schemas.of(Request.class);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("required").toString())
                .isEqualTo("[\"site\",\"size\",\"disks\",\"labels\",\"at\",\"day\",\"ref\",\"ratio\",\"extra\",\"owner_team\"]");
    }

    @Test
    void componentsMapToTheirJsonTypes() {
        JsonNode p = Schemas.of(Request.class).path("properties");

        assertThat(p.path("site").toString()).isEqualTo("{\"type\":\"string\",\"description\":\"Where it goes\"}");
        assertThat(p.path("note").toString()).isEqualTo("{\"type\":\"string\"}");
        assertThat(p.path("count").toString()).isEqualTo("{\"type\":\"integer\"}");
        assertThat(p.path("size").toString()).isEqualTo("{\"type\":\"string\",\"enum\":[\"SMALL\",\"LARGE\"]}");
        assertThat(p.path("disks").toString())
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"sizeGb\":{\"type\":\"integer\"},\"ssd\":{\"type\":\"boolean\"}},\"required\":[\"sizeGb\",\"ssd\"]}}");
        assertThat(p.path("labels").toString()).isEqualTo("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}");
        assertThat(p.path("at").toString()).isEqualTo("{\"type\":\"string\",\"format\":\"date-time\"}");
        assertThat(p.path("day").toString()).isEqualTo("{\"type\":\"string\",\"format\":\"date\"}");
        assertThat(p.path("ref").toString()).isEqualTo("{\"type\":\"string\",\"format\":\"uuid\"}");
        assertThat(p.path("ratio").toString()).isEqualTo("{\"type\":\"number\"}");
        assertThat(p.path("extra").toString()).isEqualTo("{}");
        assertThat(p.has("owner_team")).isTrue();
    }

    @Test
    void aTypeWithoutADerivableSchemaNeedsAnExplicitOne() {
        assertThatThrownBy(() -> Schemas.of(NotARecord.class)).hasMessageContaining("give the schema explicitly");
        assertThatThrownBy(() -> Schemas.of(Loop.class)).hasMessageContaining("refers to itself");
    }

    @Test
    void problemsSayWhatDoesNotConform() {
        ObjectNode schema = Schemas.of(Disk.class);

        assertThat(Schemas.problems(schema, Json.MAPPER.valueToTree(new Disk(10, true)))).isEmpty();
        assertThat(Schemas.problems(schema, Json.MAPPER.createObjectNode().put("sizeGb", "ten")))
                .anyMatch(m -> m.contains("ssd"))
                .anyMatch(m -> m.contains("sizeGb"));
    }
}
