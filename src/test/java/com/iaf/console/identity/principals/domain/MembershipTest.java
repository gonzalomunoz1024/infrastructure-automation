package com.iaf.console.identity.principals.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The two rules every change of a membership's groups goes through: the admin's and the identity provider's. */
class MembershipTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);
    private static final User DANA = User.create("user_dana", "dana@example.com", "Dana", null, null, NOW);

    @Test
    void anAdminsMembershipHoldsNothingThroughTheIdentityProviderAndASignInsHoldsEverything() {
        Membership admin = Membership.create("123456789012", DANA, List.of("grp_members"), NOW);
        assertThat(admin.idpGroupIds()).isEmpty();
        assertThat(admin.excludedGroupIds()).isEmpty();
        Membership mapped = Membership.mapped("123456789012", DANA, List.of("grp_members"), NOW);
        assertThat(mapped.idpGroupIds()).containsExactly("grp_members");
        assertThat(mapped.version()).isEqualTo(1);
    }

    @Test
    void aDerivedGroupIsHeldAndAnExcludedOneIsNotAndAStoredMembershipWithoutTheListsReadsEmpty() {
        Membership m = new Membership("123456789012", "user_dana", "dana@example.com", null, UserStatus.active, List.of("grp_a"), 3, NOW, NOW,
                List.of("grp_a", "grp_gone"), List.of("grp_a", "grp_b"));
        assertThat(m.idpGroupIds()).containsExactly("grp_a");
        assertThat(m.excludedGroupIds()).containsExactly("grp_b");
        Membership old = new Membership("123456789012", "user_dana", "dana@example.com", null, UserStatus.active, List.of("grp_a"), 3, NOW, NOW, null, null);
        assertThat(old.idpGroupIds()).isEmpty();
        assertThat(old.excludedGroupIds()).isEmpty();
    }

    @Test
    void anAdminTakingADerivedGroupExcludesItAndGivingItBackMakesItTheAdmins() {
        Membership m = Membership.mapped("123456789012", DANA, List.of("grp_members", "grp_sre"), NOW);
        Membership removed = m.regrouped(List.of("grp_sre"), Set.of(), LATER);
        assertThat(removed.groupIds()).containsExactly("grp_sre");
        assertThat(removed.idpGroupIds()).as("a kept group stays derived").containsExactly("grp_sre");
        assertThat(removed.excludedGroupIds()).containsExactly("grp_members");
        assertThat(removed.version()).isEqualTo(2);
        assertThat(removed.updatedAt()).isEqualTo(LATER);

        Membership back = removed.regrouped(List.of("grp_sre", "grp_members"), Set.of(), LATER);
        assertThat(back.excludedGroupIds()).isEmpty();
        assertThat(back.idpGroupIds()).as("the admin's now").containsExactly("grp_sre");
        assertThat(back.synced(Set.of("grp_sre"), Set.of(), LATER).removed()).as("so no sync removes it").isEmpty();
    }

    @Test
    void anAdminTakingAnAdminsGroupExcludesItOnlyWhenTheGroupMapsTheIdentityProvider() {
        Membership m = Membership.create("123456789012", DANA, List.of("grp_members", "grp_viewers"), NOW);
        Membership out = m.regrouped(List.of(), Set.of("grp_members"), LATER);
        assertThat(out.excludedGroupIds()).as("a sync would hand grp_members back").containsExactly("grp_members");
        assertThat(out.synced(Set.of("grp_members"), Set.of(), LATER).added()).isEmpty();
        assertThat(m.changed(null, null, List.of(), LATER).excludedGroupIds()).as("without the mapped groups only derived ones are excluded").isEmpty();
    }

    @Test
    void aChangeOfNameOrStatusAloneKeepsTheGroupsAsTheyWere() {
        Membership m = Membership.mapped("123456789012", DANA, List.of("grp_members"), NOW).regrouped(List.of("grp_members", "grp_x"), Set.of(), NOW);
        Membership suspended = m.changed("Dana R", UserStatus.suspended, null, Set.of(), LATER);
        assertThat(suspended.groupIds()).isEqualTo(m.groupIds());
        assertThat(suspended.idpGroupIds()).isEqualTo(m.idpGroupIds());
        assertThat(suspended.name()).isEqualTo("Dana R");
        assertThat(suspended.status()).isEqualTo(UserStatus.suspended);
    }

    @Test
    void aSyncAddsWhatIsMappedNowAndRemovesOnlyWhatTheIdentityProviderGave() {
        Membership m = Membership.create("123456789012", DANA, List.of("grp_owners"), NOW);
        Membership.Synced first = m.synced(List.of("grp_members", "grp_members"), Set.of(), LATER);
        assertThat(first.changed()).isTrue();
        assertThat(first.added()).containsExactly("grp_members");
        assertThat(first.membership().groupIds()).containsExactly("grp_owners", "grp_members");
        assertThat(first.membership().idpGroupIds()).containsExactly("grp_members");
        assertThat(first.membership().version()).isEqualTo(2);

        Membership.Synced second = first.membership().synced(List.of(), Set.of(), LATER);
        assertThat(second.removed()).containsExactly("grp_members");
        assertThat(second.membership().groupIds()).as("the admin's group stays").containsExactly("grp_owners");
        assertThat(second.membership().idpGroupIds()).isEmpty();
        assertThat(second.membership().version()).isEqualTo(3);
    }

    @Test
    void aSyncWithNothingToChangeKeepsTheVersionAndLeavesExclusionsAndTheAdminsGroupsAlone() {
        Membership m = Membership.create("123456789012", DANA, List.of("grp_members"), NOW).regrouped(List.of(), Set.of("grp_members"), NOW);
        Membership.Synced excluded = m.synced(List.of("grp_members"), Set.of(), LATER);
        assertThat(excluded.changed()).isFalse();
        assertThat(excluded.membership()).isSameAs(m);

        Membership admins = Membership.create("123456789012", DANA, List.of("grp_members"), NOW);
        Membership.Synced held = admins.synced(List.of("grp_members"), Set.of(), LATER);
        assertThat(held.changed()).isFalse();
        assertThat(held.membership().idpGroupIds()).as("an admin's group the provider also maps stays the admin's").isEmpty();
        assertThat(admins.synced(List.of(), Set.of(), LATER).changed()).isFalse();
    }

    @Test
    void aSyncKeepsWhatTheCallerKeepsAndSaysSo() {
        Membership owner = Membership.mapped("123456789012", DANA, List.of("grp_owners", "grp_members"), NOW);
        Membership.Synced synced = owner.synced(List.of(), Set.of("grp_owners"), LATER);
        assertThat(synced.kept()).containsExactly("grp_owners");
        assertThat(synced.removed()).containsExactly("grp_members");
        assertThat(synced.membership().groupIds()).containsExactly("grp_owners");
        assertThat(synced.membership().idpGroupIds()).as("still the provider's, so it goes once another owner exists").containsExactly("grp_owners");

        Membership.Synced onlyKept = synced.membership().synced(List.of(), Set.of("grp_owners"), LATER);
        assertThat(onlyKept.changed()).isFalse();
        assertThat(onlyKept.kept()).containsExactly("grp_owners");
    }
}
