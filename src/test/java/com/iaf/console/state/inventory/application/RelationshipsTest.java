package com.iaf.console.state.inventory.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.attributes;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.state.inventory.domain.BlastRadius;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Lineage;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceGraph;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceRef;
import com.iaf.console.state.inventory.domain.ResourceState;

/**
 * Lineage, blast radius and the map over one image that three VMs (one in another account) were
 * built from, a volume built from one of them, and a parent the console does not manage.
 */
class RelationshipsTest {

    private static final String OTHER = "210987654321";

    private final InventoryHarness h = new InventoryHarness();
    private Resource image;
    private Resource vm1;
    private Resource vm2;
    private Resource vm3;
    private Resource volume;

    @BeforeEach
    void world() {
        image = h.imported(ACCOUNT, "compute", "image", "ubuntu", Json.object().put("os", "ubuntu"));
        vm1 = h.imported(ACCOUNT, "compute", "vm", "web-1", attributes("web-1", 2, 4), "image-ubuntu", "golden-x");
        vm2 = h.imported(ACCOUNT, "compute", "vm", "web-2", attributes("web-2", 2, 4), "image-ubuntu");
        vm3 = h.imported(OTHER, "compute", "vm", "batch-1", attributes("batch-1", 8, 16), "image-ubuntu");
        volume = h.imported(ACCOUNT, "storage", "volume", "data-1", Json.object().put("sizeGb", 100), "vm-web-1");
    }

    @Test
    void lineageWalksUpAndDownWithinTheAccount() {
        Lineage lineage = h.relationships.lineage(InventoryHarness.scope(ACCOUNT), vm1.wfrn(), 32, 200, false).block();

        assertThat(lineage.resource().wfrn()).isEqualTo(vm1.wfrn());
        assertThat(lineage.nodes()).extracting(ResourceRef::wfrn).first().isEqualTo(vm1.wfrn());
        assertThat(lineage.nodes()).extracting(ResourceRef::wfrn).containsExactlyInAnyOrder(vm1.wfrn(), image.wfrn(), volume.wfrn());
        assertThat(lineage.builtFrom()).extracting(ResourceRef::wfrn).containsExactly(image.wfrn());
        assertThat(lineage.dependents()).extracting(ResourceRef::wfrn).containsExactly(volume.wfrn());
        assertThat(lineage.edges()).containsExactlyInAnyOrder(new Lineage.Edge(image.wfrn(), vm1.wfrn(), null),
                new Lineage.Edge(vm1.wfrn(), volume.wfrn(), null));
        assertThat(lineage.unmanaged()).containsExactly("golden-x");
        assertThat(lineage.hidden()).isZero();
        assertThat(lineage.truncated()).isFalse();
    }

    @Test
    void anotherAccountsDependentsAreCountedNotShown() {
        Lineage mine = h.relationships.lineage(InventoryHarness.scope(ACCOUNT), image.wfrn(), 32, 200, false).block();
        assertThat(mine.dependents()).extracting(ResourceRef::wfrn).containsExactlyInAnyOrder(vm1.wfrn(), vm2.wfrn());
        assertThat(mine.nodes()).extracting(ResourceRef::wfrn).contains(volume.wfrn()).doesNotContain(vm3.wfrn());
        assertThat(mine.hidden()).isEqualTo(1);

        Lineage all = h.relationships.lineage(InventoryHarness.scope(ACCOUNT), image.wfrn(), 32, 200, true).block();
        assertThat(all.dependents()).extracting(ResourceRef::wfrn).contains(vm3.wfrn());
        assertThat(all.hidden()).isZero();
    }

    @Test
    void depthAndLimitCutTheWalkShort() {
        Lineage oneHop = h.relationships.lineage(InventoryHarness.scope(ACCOUNT), image.wfrn(), 1, 200, false).block();
        assertThat(oneHop.nodes()).extracting(ResourceRef::wfrn).doesNotContain(volume.wfrn());

        Lineage two = h.relationships.lineage(InventoryHarness.scope(ACCOUNT), image.wfrn(), 32, 2, false).block();
        assertThat(two.nodes()).hasSize(2);
        assertThat(two.truncated()).isTrue();

        assertThatThrownBy(() -> h.relationships.lineage(InventoryHarness.scope(ACCOUNT), image.wfrn(), 33, 200, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.relationships.lineage(InventoryHarness.scope(OTHER), image.wfrn(), 32, 200, false).block())
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDeletedParentStaysOnTheLineageAsATombstone() {
        h.inventory.deleted(InventoryHarness.scope(ACCOUNT), image.wfrn(), new Commands.Deletion(null, null, by("platform"))).block();

        Lineage lineage = h.relationships.lineage(InventoryHarness.scope(ACCOUNT), vm1.wfrn(), 32, 200, false).block();
        assertThat(lineage.builtFrom()).singleElement().satisfies(parent -> {
            assertThat(parent.wfrn()).isEqualTo(image.wfrn());
            assertThat(parent.state()).isEqualTo(ResourceState.DELETED);
        });
    }

    @Test
    void theBlastRadiusCountsEveryAccountAndNamesOnlyTheCallers() {
        BlastRadius radius = h.relationships.blastRadius(InventoryHarness.scope(ACCOUNT), List.of(image.wfrn()), 32, false).block();

        assertThat(radius.total()).isEqualTo(4);
        assertThat(radius.depth()).isEqualTo(2);
        assertThat(radius.accounts()).containsExactly(new BlastRadius.AccountCount(ACCOUNT, 3, true), new BlastRadius.AccountCount(OTHER, 1, false));
        assertThat(radius.types()).containsExactly(new BlastRadius.TypeCount("vm", 3), new BlastRadius.TypeCount("volume", 1));
        assertThat(radius.items()).extracting(ResourceRef::wfrn).containsExactlyInAnyOrder(vm1.wfrn(), vm2.wfrn(), volume.wfrn());

        BlastRadius bulk = h.relationships.blastRadius(InventoryHarness.scope(ACCOUNT), List.of(vm1.wfrn(), vm2.wfrn()), 32, false).block();
        assertThat(bulk.total()).as("the roots themselves are not counted").isEqualTo(1);
        assertThat(bulk.items()).extracting(ResourceRef::wfrn).containsExactly(volume.wfrn());

        BlastRadius leaf = h.relationships.blastRadius(InventoryHarness.scope(ACCOUNT), List.of(volume.wfrn()), 32, false).block();
        assertThat(leaf.total()).isZero();
        assertThat(leaf.accounts()).isEmpty();
    }

    @Test
    void theMapDrawsTheAccountAndSumsUpTheRest() {
        ResourceGraph map = h.relationships.graph(List.of(InventoryHarness.scope(ACCOUNT)), ResourceQuery.all(), 500, false).block();

        assertThat(map.nodes()).extracting(ResourceGraph.Node::wfrn).containsExactlyInAnyOrder(image.wfrn(), vm1.wfrn(), vm2.wfrn(), volume.wfrn());
        assertThat(map.nodes()).allSatisfy(n -> assertThat(n.context()).isNull());
        assertThat(map.edges()).extracting(Lineage.Edge::from, Lineage.Edge::to).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(image.wfrn(), vm1.wfrn()), org.assertj.core.groups.Tuple.tuple(image.wfrn(), vm2.wfrn()),
                org.assertj.core.groups.Tuple.tuple(vm1.wfrn(), volume.wfrn()));
        assertThat(map.external()).containsExactly(new ResourceGraph.External(OTHER, "vm", "dependent", 1));
        assertThat(map.unmanaged()).containsExactly("golden-x");

        ResourceGraph vms = h.relationships.graph(List.of(InventoryHarness.scope(ACCOUNT)), ResourceQuery.all().withType("vm"), 500, false).block();
        assertThat(vms.nodes()).filteredOn(n -> Boolean.TRUE.equals(n.context())).extracting(ResourceGraph.Node::wfrn)
                .containsExactlyInAnyOrder(image.wfrn(), volume.wfrn());
        assertThat(vms.edges()).hasSize(3);

        ResourceGraph both = h.relationships.graph(List.of(InventoryHarness.scope(ACCOUNT), InventoryHarness.scope(OTHER)), ResourceQuery.all(), 500,
                true).block();
        assertThat(both.nodes()).hasSize(5);
        assertThat(both.external()).isEmpty();

        ResourceGraph small = h.relationships.graph(List.of(InventoryHarness.scope(ACCOUNT)), ResourceQuery.all(), 2, false).block();
        assertThat(small.nodes()).hasSize(2);
        assertThat(small.truncated()).isTrue();
    }

    @Test
    void aReRegistrationThatOnlyExtendsTheLineageIsRecorded() {
        Resource before = h.loader.load(InventoryHarness.scope(ACCOUNT), vm2.wfrn()).block();
        Resource after = h.inventory.registered(InventoryHarness.scope(ACCOUNT), vm2.wfrn(), new Commands.Registration("job_9", "attach-image", null,
                before.registryId(), before.providerId(), before.name(), before.attributes(), by("platform"), List.of("image-ubuntu", "golden-y"))).block();
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.builtFrom()).containsExactly("image-ubuntu", "golden-y");

        Resource same = h.inventory.registered(InventoryHarness.scope(ACCOUNT), vm2.wfrn(), new Commands.Registration("job_9", "attach-image", null,
                before.registryId(), before.providerId(), before.name(), before.attributes(), by("platform"), null)).block();
        assertThat(same.version()).as("a registration that does not know the lineage keeps it").isEqualTo(after.version());
        assertThat(same.builtFrom()).containsExactly("image-ubuntu", "golden-y");
    }
}
