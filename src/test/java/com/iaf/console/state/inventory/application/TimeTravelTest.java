package com.iaf.console.state.inventory.application;

import static com.iaf.console.state.inventory.domain.Fixtures.ACCOUNT;
import static com.iaf.console.state.inventory.domain.Fixtures.T0;
import static com.iaf.console.state.inventory.domain.Fixtures.attributes;
import static com.iaf.console.state.inventory.domain.Fixtures.by;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.state.inventory.domain.Commands;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.ResourceState;
import com.iaf.console.state.inventory.domain.RevertPlan;

/** A resource and an account as they were at an instant, and the plan that reverts a resource to it. */
class TimeTravelTest {

    private final InventoryHarness h = new InventoryHarness();

    private Resource resize(Resource r, int cpu) {
        Resource now = h.loader.load(InventoryHarness.scope(ACCOUNT), r.wfrn()).block();
        return h.inventory.registered(InventoryHarness.scope(ACCOUNT), r.wfrn(), new Commands.Registration("job_resize", "resize-vm", null,
                now.registryId(), now.providerId(), now.name(), attributes(now.name(), cpu, 4), by("platform"))).block();
    }

    @Test
    void aResourceAsItWasAtAnInstant() {
        Resource web = h.imported(ACCOUNT, "compute", "vm", "web", attributes("web", 2, 4));
        h.clock.advance(Duration.ofHours(1));
        resize(web, 4);

        Resource then = h.queries.get(InventoryHarness.scope(ACCOUNT), web.wfrn(), null, T0.plus(Duration.ofMinutes(30)), false).block();
        assertThat(then.version()).isEqualTo(1);
        assertThat(then.attributes().path("cpu").asInt()).isEqualTo(2);
        Resource later = h.queries.get(InventoryHarness.scope(ACCOUNT), web.wfrn(), null, T0.plus(Duration.ofMinutes(90)), false).block();
        assertThat(later.attributes().path("cpu").asInt()).isEqualTo(4);

        assertThatThrownBy(() -> h.queries.get(InventoryHarness.scope(ACCOUNT), web.wfrn(), null, T0.minusSeconds(1), false).block())
                .isInstanceOf(NotFoundException.class).hasMessageStartingWith("resource.notYet");
        assertThatThrownBy(() -> h.queries.get(InventoryHarness.scope(ACCOUNT), web.wfrn(), 1L, T0, false).block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAccountAsItWasAtAnInstant() {
        Resource early = h.imported(ACCOUNT, "compute", "vm", "early", attributes("early", 2, 4));
        Resource gone = h.imported(ACCOUNT, "compute", "vm", "gone", attributes("gone", 2, 4));
        h.clock.advance(Duration.ofHours(1));
        resize(early, 8);
        h.inventory.deleted(InventoryHarness.scope(ACCOUNT), gone.wfrn(), new Commands.Deletion(null, null, by("platform"))).block();
        h.imported(ACCOUNT, "compute", "vm", "late", attributes("late", 2, 4));

        Page<Resource> then = h.queries.listAt(InventoryHarness.scope(ACCOUNT), ResourceQuery.all(), T0.plus(Duration.ofMinutes(30))).block();
        assertThat(then.items()).extracting(Resource::name).containsExactlyInAnyOrder("early", "gone");
        assertThat(then.items()).filteredOn(r -> r.name().equals("early")).singleElement()
                .satisfies(r -> assertThat(r.attributes().path("cpu").asInt()).isEqualTo(2));
        assertThat(then.next()).isNull();

        Page<Resource> now = h.queries.listAt(InventoryHarness.scope(ACCOUNT), ResourceQuery.all(), T0.plus(Duration.ofHours(2))).block();
        assertThat(now.items()).extracting(Resource::name).containsExactlyInAnyOrder("early", "late");

        Page<Resource> paged = h.queries.listAt(InventoryHarness.scope(ACCOUNT), ResourceQuery.all().withLimit(1), T0.plus(Duration.ofMinutes(30)))
                .block();
        assertThat(paged.items()).hasSize(1);
        assertThat(paged.next()).isNotNull();
    }

    @Test
    void theRevertPlanIsTheMergePatchBackAndTheTagChanges() {
        Resource web = h.imported(ACCOUNT, "compute", "vm", "web", attributes("web", 2, 4).put("disk", 40));
        h.inventory.tag(InventoryHarness.scope(ACCOUNT), web.wfrn(), new Commands.Tag(Map.of("owner", "dana"), List.of(), by("dana"))).block();
        h.clock.advance(Duration.ofHours(1));
        Resource now = h.loader.load(InventoryHarness.scope(ACCOUNT), web.wfrn()).block();
        h.inventory.registered(InventoryHarness.scope(ACCOUNT), web.wfrn(), new Commands.Registration("job_resize", "resize-vm", null, now.registryId(),
                now.providerId(), now.name(), attributes("web", 4, 4).put("gpu", "a100"), by("platform"))).block();
        h.inventory.tag(InventoryHarness.scope(ACCOUNT), web.wfrn(), new Commands.Tag(Map.of("owner", "raj", "cost-centre", "cc-7"), List.of(), by("raj")))
                .block();

        RevertPlan plan = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), web.wfrn(), null, T0.plus(Duration.ofMinutes(30)), false).block();
        assertThat(plan.possible()).isTrue();
        assertThat(plan.from().version()).isEqualTo(4);
        assertThat(plan.to().version()).isEqualTo(2);
        assertThat(plan.changes()).isEqualTo(Json.object().put("disk", 40).put("cpu", 2).putNull("gpu"));
        assertThat(plan.fields()).extracting(RevertPlan.FieldChange::path).containsExactlyInAnyOrder("/cpu", "/disk", "/gpu");
        assertThat(plan.tags().set()).containsExactly(Map.entry("owner", "dana"));
        assertThat(plan.tags().removed()).containsExactly("cost-centre");

        RevertPlan same = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), web.wfrn(), 4L, null, false).block();
        assertThat(same.possible()).isFalse();
        assertThat(same.reason()).isEqualTo("resource.unchanged");
    }

    @Test
    void aResourceThatIsGoneOrBusyCannotBeReverted() {
        Resource web = h.imported(ACCOUNT, "compute", "vm", "web", attributes("web", 2, 4));
        h.clock.advance(Duration.ofHours(1));
        h.inventory.deleted(InventoryHarness.scope(ACCOUNT), web.wfrn(), new Commands.Deletion(null, null, by("platform"))).block();
        RevertPlan plan = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), web.wfrn(), 1L, null, false).block();
        assertThat(plan.possible()).isFalse();
        assertThat(plan.reason()).isEqualTo("resource.deleted");
        assertThat(plan.from().state()).isEqualTo(ResourceState.DELETED);

        RevertPlan before = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), web.wfrn(), null, T0.minusSeconds(5), false).block();
        assertThat(before.reason()).isEqualTo("resource.notYet");
    }

    @Test
    void aSensitiveValueIsComparedInClearAndAnsweredMasked() {
        Resource db = h.imported(ACCOUNT, "data", "database", "orders", Json.object().put("name", "orders").put("engine", "postgres")
                .put("secret", "first"));
        h.clock.advance(Duration.ofHours(1));
        Resource now = h.loader.load(InventoryHarness.scope(ACCOUNT), db.wfrn()).block();
        h.inventory.registered(InventoryHarness.scope(ACCOUNT), db.wfrn(), new Commands.Registration("job_rotate", "rotate-secret", null,
                now.registryId(), now.providerId(), now.name(), Json.object().put("name", "orders").put("engine", "postgres").put("secret", "second"),
                by("platform"))).block();

        RevertPlan masked = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), db.wfrn(), 1L, null, false).block();
        assertThat(masked.changes()).isEqualTo(Json.object().put("secret", "***"));
        assertThat(masked.fields()).singleElement().satisfies(f -> {
            assertThat(f.now().asText()).isEqualTo("***");
            assertThat(f.then().asText()).isEqualTo("***");
        });
        RevertPlan open = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), db.wfrn(), 1L, null, true).block();
        assertThat(open.changes()).isEqualTo(Json.object().put("secret", "first"));

        RevertPlan unchanged = h.queries.revertPlan(InventoryHarness.scope(ACCOUNT), db.wfrn(), 2L, null, false).block();
        assertThat(unchanged.reason()).as("two seals of one value differ; the plan compares the values").isEqualTo("resource.unchanged");
    }
}
