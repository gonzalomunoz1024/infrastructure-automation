package com.iaf.platform.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.domain.RouteTable.Kind;
import com.iaf.platform.catalog.domain.RouteTable.RouteMatch;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** The routes of the sample content: every published workflow and every resource type. */
class RouteTableTest {

    static final RouteTable TABLE = RouteTable.of(SampleCatalog.workflows(), SampleCatalog.types());

    @Test
    void matchesTheInvokeRouteOfAWorkflowWithItsPathParameters() {
        RouteMatch m = TABLE.match("POST", "/api/v1/vms/vm-1042:restart").orElseThrow();
        assertThat(m.route().kind()).isEqualTo(Kind.INVOKE);
        assertThat(m.route().workflow().name()).isEqualTo("vm-restart");
        assertThat(m.params()).isEqualTo(Map.of("vmId", "vm-1042"));
    }

    @Test
    void matchesBatchAndPreviewSuffixes() {
        // a path that ends in a parameter batches on the collection: each row names its own VM
        RouteMatch batch = TABLE.match("POST", "/api/v1/vms:restart:batch").orElseThrow();
        assertThat(batch.route().kind()).isEqualTo(Kind.BATCH);
        assertThat(batch.route().workflow().name()).isEqualTo("vm-restart");
        assertThat(batch.params()).isEmpty();
        assertThat(TABLE.match("POST", "/api/v1/vms/vm-1:restart:batch")).isEmpty();
        assertThat(TABLE.match("POST", "/api/v1/vms:delete:batch")).map(r -> r.route().workflow().name()).contains("delete-vm");
        assertThat(TABLE.match("POST", "/api/v1/vms:batch")).map(r -> r.route().workflow().name()).contains("create-vm");
        RouteMatch preview = TABLE.match("POST", "/api/v1/vms/vm-1:restart:preview").orElseThrow();
        assertThat(preview.route().kind()).isEqualTo(Kind.PREVIEW);
        // build-vm-image has preview: false, so there is no :preview route, but :batch is always there
        assertThat(TABLE.match("POST", "/api/v1/images:build:preview")).isEmpty();
        assertThat(TABLE.match("POST", "/api/v1/images:build:batch")).map(r -> r.route().workflow().name()).contains("build-vm-image");
    }

    @Test
    void theMethodTellsWorkflowsOnTheSamePathApart() {
        assertThat(TABLE.match("POST", "/api/v1/vms")).map(r -> r.route().workflow().name()).contains("create-vm");
        assertThat(TABLE.match("DELETE", "/api/v1/vms/vm-1")).map(r -> r.route().workflow().name()).contains("delete-vm");
        assertThat(TABLE.match("GET", "/api/v1/vms")).map(r -> r.route().kind()).contains(Kind.LIST_RESOURCES);
        assertThat(TABLE.match("GET", "/api/v1/vms/vm-1")).map(r -> r.route().kind()).contains(Kind.GET_RESOURCE);
        assertThat(TABLE.match("PUT", "/api/v1/vms")).isEmpty();
        assertThat(TABLE.knowsPath("/api/v1/vms")).isTrue();
        assertThat(TABLE.knowsPath("/api/v1/nothing")).isFalse();
    }

    @Test
    void resourceReadsExistForEveryType() {
        for (var t : SampleCatalog.types()) {
            RouteMatch list = TABLE.match("GET", "/api/v1/" + t.plural()).orElseThrow();
            assertThat(list.route().type().name()).isEqualTo(t.name());
            RouteMatch get = TABLE.match("GET", "/api/v1/" + t.plural() + "/x-1").orElseThrow();
            assertThat(get.params()).containsEntry("id", "x-1");
        }
    }

    @Test
    void platformRoutesAreNeverShadowed() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: job-restart, title: Job restart, noun: job, verb: restart, owner: team-compute, version: 1.0.0 }
                api:
                  method: POST
                  path: /api/v1/jobs/{id}:restart
                  input: { type: object, properties: { id: { type: string } } }
                workflow:
                  do:
                    - noop:
                        set: { x: 1 }
                """;
        List<WorkflowRecord> records = new ArrayList<>(SampleCatalog.workflows());
        records.add(WorkflowRecord.of(WorkflowYaml.parse(yaml), WorkflowStatus.PUBLISHED, "workflows/x/job-restart.yaml", Instant.EPOCH, "test"));
        RouteTable table = RouteTable.of(records, SampleCatalog.types());
        assertThat(table.match("POST", "/api/v1/jobs/job_1:restart")).isEmpty();
        assertThat(table.match("POST", "/api/v1/jobs/job_1:restart:batch")).isEmpty();
        assertThat(table.match("POST", "/api/v1/jobs:restart:batch")).isEmpty();
        assertThat(table.routes()).noneMatch(r -> r.path().startsWith("/api/v1/jobs"));
        for (String reserved : ReservedNouns.NOUNS) {
            assertThat(table.match("GET", "/api/v1/" + reserved)).as(reserved).isEmpty();
            assertThat(table.match("POST", "/api/v1/" + reserved + "/x")).as(reserved).isEmpty();
        }
    }

    @Test
    void aWorkflowWithoutAnExplicitPathGetsTheInferredOne() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: cluster-scale, title: Scale cluster, noun: cluster, verb: scale, owner: team-data, version: 1.0.0 }
                workflow:
                  do:
                    - noop:
                        set: { x: 1 }
                """;
        WorkflowRecord r = WorkflowRecord.of(WorkflowYaml.parse(yaml), WorkflowStatus.PUBLISHED, "x", Instant.EPOCH, "test");
        RouteTable table = RouteTable.of(List.of(r), List.of());
        assertThat(table.match("POST", "/api/v1/clusters:scale")).isPresent();
        assertThat(table.match("POST", "/api/v1/clusters:scale:batch")).isPresent();
        assertThat(table.match("POST", "/api/v1/clusters:scale:preview")).isEmpty();
    }
}
