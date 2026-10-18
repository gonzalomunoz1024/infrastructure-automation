package com.iaf.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RoleTest {

    @Test
    void anInstanceHasBothRolesUnlessToldOtherwise() {
        assertThat(Role.of(new MockEnvironment())).containsExactly(Role.API, Role.WORKER);
    }

    @Test
    void theRolesReadAsCommaSeparatedTextOrAsAList() {
        assertThat(Role.of(new MockEnvironment().withProperty("platform.roles", "worker"))).containsExactly(Role.WORKER);
        assertThat(Role.of(new MockEnvironment().withProperty("platform.roles", "API, worker"))).containsExactly(Role.API, Role.WORKER);
        assertThat(Role.of(new MockEnvironment().withProperty("platform.roles[0]", "api"))).containsExactly(Role.API);
    }

    @Test
    void anInstanceNeedsARole() {
        assertThatThrownBy(() -> RoleWebFilter.check(EnumSet.noneOf(Role.class), "temporal"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("api, worker or both");
    }

    @Test
    void splittingTheRolesNeedsTheTemporalEngine() {
        assertThatThrownBy(() -> RoleWebFilter.check(Set.of(Role.WORKER), "in-memory"))
                .isInstanceOf(IllegalStateException.class).hasMessage("platform.roles [worker] needs the temporal profile: the in-memory engine runs "
                        + "a job in the process that started it, so the instance needs both roles");
        assertThat(RoleWebFilter.check(Set.of(Role.WORKER), "temporal")).containsExactly(Role.WORKER);
        assertThat(RoleWebFilter.check(EnumSet.allOf(Role.class), "in-memory")).containsExactly(Role.API, Role.WORKER);
    }
}
