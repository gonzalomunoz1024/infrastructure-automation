package com.iaf.platform.workflows.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;

/** A change request as the stores keep it (a JSON document of the record) and what its copies carry along. */
class ChangeRequestTest {

    /** A mapper as Spring Boot builds the application's: ISO instants, null fields left out. */
    static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    static ChangeRequest open(List<String> requires) {
        return new ChangeRequest("cr_1", ChangeRequest.Kind.WORKFLOW, "ticket-check", "0.1.0", "change/ticket-check-0.1.0", "Publish Check a ticket 0.1.0",
                "junior", ChangeRequest.Status.OPEN, List.of(new Check("requires", "Requires", "pending", "waiting for cr_7 (provider ticketing 0.1.0) to merge")),
                "all", "kind: Workflow\n", "", NOW, null, null, null, requires);
    }

    @Test
    void whatItRequiresSurvivesTheStoredDocumentAndADocumentWithoutItReadsAsRequiringNothing() throws Exception {
        ChangeRequest linked = open(List.of("cr_7"));
        String doc = JSON.writeValueAsString(linked);
        assertThat(JSON.readTree(doc).path("requires").toString()).isEqualTo("[\"cr_7\"]");
        assertThat(JSON.readValue(doc, ChangeRequest.class)).isEqualTo(linked);

        // what the stores hold from before: no requires at all
        ChangeRequest alone = new ChangeRequest("cr_1", ChangeRequest.Kind.WORKFLOW, "ticket-check", "0.1.0", "change/ticket-check-0.1.0",
                "Publish Check a ticket 0.1.0", "junior", ChangeRequest.Status.OPEN, List.of(), "all", "kind: Workflow\n", "", NOW, null, null, null);
        String older = JSON.writeValueAsString(alone);
        assertThat(JSON.readTree(older).has("requires")).isFalse();
        ChangeRequest read = JSON.readValue(older, ChangeRequest.class);
        assertThat(read).isEqualTo(alone);
        assertThat(read.requires()).isNull();
        assertThat(read.required()).isEmpty();
    }

    @Test
    void itsCopiesKeepWhatItRequiresAndACheckReplacesTheOneOfItsId() {
        ChangeRequest linked = open(List.of("cr_7"));
        assertThat(linked.withStatus(ChangeRequest.Status.MERGED, NOW, "maya").requires()).containsExactly("cr_7");
        assertThat(linked.withUrl("https://github.com/acme/content/pull/42").requires()).containsExactly("cr_7");

        ChangeRequest judged = linked.withCheck(new Check("requires", "Requires", "passed", "merged: cr_7 (provider ticketing 0.1.0)"));
        assertThat(judged.checks()).extracting(Check::id, Check::status).containsExactly(org.assertj.core.groups.Tuple.tuple("requires", "passed"));
        assertThat(judged.requires()).containsExactly("cr_7");
        assertThat(linked.withCheck(new Check("yaml", "YAML", "passed", "canonical")).checks()).extracting(Check::id).containsExactly("requires", "yaml");
        assertThat(linked.checks()).as("the original is untouched").extracting(Check::status).containsExactly("pending");
    }
}
