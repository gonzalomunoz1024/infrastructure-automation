package com.iaf.console.search.index.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.iaf.console.kit.errors.InvalidInputException;

/** The groupings a list's counts take, and how the counts of several accounts add up. */
class ResourceCountsTest {

    @Test
    void groupByNamesAFieldATagOrTheAccount() {
        assertThat(GroupBy.of(null)).isEqualTo(GroupBy.NONE);
        assertThat(GroupBy.of(" ")).isEqualTo(GroupBy.NONE);
        assertThat(GroupBy.of("type").field()).isEqualTo("type");
        assertThat(GroupBy.of("service").field()).isEqualTo("service");
        assertThat(GroupBy.of("state").field()).isEqualTo("state");
        assertThat(GroupBy.of("region").field()).isEqualTo("region");
        assertThat(GroupBy.of("account").field()).as("the application groups by account").isNull();
        GroupBy env = GroupBy.of("tag:env");
        assertThat(env.kind()).isEqualTo(GroupBy.Kind.TAG);
        assertThat(env.field()).isEqualTo("tagsFlat");
        IndexedResource d = Fixtures.document(Fixtures.VM, "web", 1, Map.of("env", "prod"), Fixtures.T0);
        assertThat(env.keyOf(d)).isEqualTo("prod");
        assertThat(GroupBy.of("tag:team").keyOf(d)).as("a resource without the tag is in no group").isNull();
        assertThat(GroupBy.of("type").keyOf(d)).isEqualTo("vm");
        assertThat(GroupBy.of("account").keyOf(d)).isEqualTo(Fixtures.ACCOUNT);
        assertThatThrownBy(() -> GroupBy.of("colour")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> GroupBy.of("tag:Bad Name")).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> GroupBy.of("none")).isInstanceOf(InvalidInputException.class);
    }

    @Test
    void theCountsOfTwoAccountsAddUpByKey() {
        ResourceCounts a = ResourceCounts.exact(3, Map.of("active", 2L, "failed", 1L), 1)
                .withGroups(List.of(new CountGroup("vm", 2, Map.of("active", 1L, "failed", 1L), 1, "compute"),
                        new CountGroup("database", 1, Map.of("active", 1L), 0, "data")));
        ResourceCounts b = ResourceCounts.exact(2, Map.of("active", 2L, "deleted", 1L), 0)
                .withGroups(List.of(new CountGroup("vm", 2, Map.of("active", 2L, "deleted", 1L), 0, "compute")));
        ResourceCounts sum = a.plus(b).largest(GroupBy.MAX_GROUPS);
        assertThat(sum.total()).isEqualTo(5);
        assertThat(sum.drift()).isEqualTo(1);
        assertThat(sum.byState()).containsEntry("active", 4L).containsEntry("failed", 1L).containsEntry("deleted", 1L).containsEntry("pending", 0L);
        assertThat(sum.groups()).extracting(CountGroup::key).containsExactly("vm", "database");
        CountGroup vm = sum.groups().get(0);
        assertThat(vm.total()).isEqualTo(4);
        assertThat(vm.byState()).containsEntry("active", 3L).containsEntry("deleted", 1L);
        assertThat(vm.service()).isEqualTo("compute");
        assertThat(a.plus(ResourceCounts.exact(0, Map.of(), 0)).groups()).as("an account without groups adds none").hasSize(2);
        assertThat(ResourceCounts.exact(1, Map.of(), 0).plus(ResourceCounts.exact(1, Map.of(), 0)).groups()).as("no grouping, no groups").isNull();
    }

    @Test
    void theLargestGroupsAreKeptAndEmptyOnesDropped() {
        List<CountGroup> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new CountGroup(String.format("k%03d", i), i % 7, Map.of("active", (long) (i % 7)), 0, null));
        }
        many.add(new CountGroup("tombstones-only", 0, Map.of("deleted", 4L), 0, null));
        many.add(new CountGroup("nothing", 0, Map.of(), 0, null));
        ResourceCounts kept = ResourceCounts.exact(0, Map.of(), 0).withGroups(many).largest(GroupBy.MAX_GROUPS);
        assertThat(kept.groups()).hasSize(100);
        assertThat(kept.groups().get(0).total()).isEqualTo(6);
        assertThat(kept.groups()).isSortedAccordingTo(CountGroup.LARGEST_FIRST);
        List<CountGroup> few = List.of(new CountGroup("tombstones-only", 0, Map.of("deleted", 4L), 0, null), new CountGroup("nothing", 0, Map.of(), 0, null));
        assertThat(ResourceCounts.exact(0, Map.of(), 0).withGroups(few).largest(GroupBy.MAX_GROUPS).groups()).extracting(CountGroup::key)
                .as("a group of tombstones keeps its chips; a group of nothing is gone").containsExactly("tombstones-only");
    }
}
