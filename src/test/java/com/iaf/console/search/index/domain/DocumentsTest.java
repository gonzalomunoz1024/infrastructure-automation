package com.iaf.console.search.index.domain;

import static com.iaf.console.search.index.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** A resource of the state service as a document: the searchable fields, the tags, the drift, the tombstone. */
class DocumentsTest {

    @Test
    void aResourceBecomesADocument() {
        var json = Fixtures.stateResource(Fixtures.DB, "orders", 5, "active", Map.of("name", "orders", "environment", "prod", "secret", "***", "engine", "pg"),
                Map.of("team", "data"), T0.plusSeconds(10));
        json.putObject("drift").put("findingId", "f-9").put("severity", "critical");
        IndexedResource doc = Documents.fromState(StateResource.of(json), Fixtures.DB_HINTS, null);
        assertThat(doc.name()).isEqualTo("orders");
        assertThat(doc.version()).isEqualTo(5);
        assertThat(doc.fields()).containsOnlyKeys("name", "environment");
        assertThat(doc.tags()).containsEntry("team", "data");
        assertThat(doc.drift()).isTrue();
        assertThat(doc.driftSeverity()).isEqualTo("critical");
        assertThat(doc.updatedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(doc.service()).isEqualTo("data");
    }

    @Test
    void aTombstoneKeepsThePreviousName() {
        IndexedResource previous = Fixtures.document(Fixtures.VM, "web-03", 3, Map.of("env", "dev"), T0);
        var tombstone = Json.object().put("error", "resource.deleted").put("wfrn", Fixtures.VM).put("deletedAt", T0.plusSeconds(60).toString())
                .put("tombstoneUntil", T0.plusSeconds(600).toString()).put("lastVersion", 4);
        IndexedResource doc = Documents.fromState(StateResource.tombstone(tombstone), Fixtures.VM_HINTS, previous);
        assertThat(doc.state()).isEqualTo(ResourceState.DELETED);
        assertThat(doc.name()).isEqualTo("web-03");
        assertThat(doc.version()).isEqualTo(4);
        assertThat(doc.tombstoneUntil()).isEqualTo(T0.plusSeconds(600));
        IndexedResource bare = Documents.fromState(StateResource.tombstone(tombstone), Fixtures.VM_HINTS, null);
        assertThat(bare.name()).isNull();
        assertThat(bare.accountId()).isEqualTo(Fixtures.ACCOUNT);
    }

    @Test
    void aMovedResourcesTombstoneHasNoEnd() {
        IndexedResource previous = Fixtures.document(Fixtures.VM, "web-03", 3, Map.of("env", "dev"), T0);
        var tombstone = Json.object().put("error", "resource.transferred").put("wfrn", Fixtures.VM).put("deletedAt", T0.plusSeconds(60).toString())
                .put("lastVersion", 4).put("transferredTo", "wfrn:wf:compute:global:" + Fixtures.OTHER_ACCOUNT + ":vm/vm-01jbx7q2m4k9d3c8f5r6t1w0yz");
        IndexedResource doc = Documents.fromState(StateResource.tombstone(tombstone), Fixtures.VM_HINTS, previous);
        assertThat(doc.state()).isEqualTo(ResourceState.DELETED);
        assertThat(doc.name()).isEqualTo("web-03");
        assertThat(doc.deletedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(doc.tombstoneUntil()).isNull();
    }

    @Test
    void whatIsNotAResourceIsNull() {
        assertThat(Documents.fromState(StateResource.of(Json.object().put("wfrn", "not-a-name")), null, null)).isNull();
        assertThat(Documents.fromState(StateResource.of(Json.object().put("wfrn", Fixtures.VM).put("state", "purple")), null, null)).isNull();
    }
}
