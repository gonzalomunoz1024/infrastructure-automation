package com.iaf.platform.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;

class NotificationTest {

    static Identity person(String id, boolean admin, String... teams) {
        return new Identity(id, id, null, List.of(teams), List.of(), admin, Identity.Kind.USER);
    }

    @Test
    void isAddressedToItsUsersAndTheMembersOfItsTeamsOnly() {
        Notification n = Notification.builder().kind(Notification.NOTIFY).users(List.of("ana")).teams(List.of("team-compute")).build();

        assertThat(n.addressedTo(person("ana", false))).isTrue();
        assertThat(n.addressedTo(person("mateo", false, "team-compute"))).isTrue();
        assertThat(n.addressedTo(person("bob", false, "team-data"))).isFalse();
        assertThat(n.addressedTo(person("root", true))).isFalse();
        assertThat(n.addressedTo(null)).isFalse();
    }

    @Test
    void audiencesAreDistinctAndBlankFree() {
        Notification n = Notification.builder().kind(Notification.NOTIFY).users(Arrays.asList("ana", " ", null, "ana"))
                .teams(List.of("team-data")).build()
                .withMoreTeams(List.of("team-data", "team-compute"));

        assertThat(n.users()).containsExactly("ana");
        assertThat(n.teams()).containsExactly("team-data", "team-compute");
        assertThat(n.severity()).isEqualTo("info");
    }
}
