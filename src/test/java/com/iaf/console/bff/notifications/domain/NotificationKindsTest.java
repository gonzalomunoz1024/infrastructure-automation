package com.iaf.console.bff.notifications.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Decision 32.1 and 32.2: the kinds, and the channels each has before a person changes anything. */
class NotificationKindsTest {

    private static final List<String> LOUD = List.of("job.failed", "job.needs-attention", "approval.requested", "access-request.created",
            "access-request.decided", "budget.alert", "digest.ready");

    @Test
    void theElevenKindsAreKnownAndTheTestIsNot() {
        assertEquals(List.of("job.succeeded", "job.failed", "job.needs-attention", "approval.requested", "approval.decided", "access-request.created",
                "access-request.decided", "drift.found", "quota.near-limit", "budget.alert", "digest.ready"), NotificationKinds.ALL);
        assertTrue(NotificationKinds.ALL.stream().allMatch(NotificationKinds::isKnown));
        assertFalse(NotificationKinds.isKnown("test"));
        assertFalse(NotificationKinds.isKnown("job.exploded"));
    }

    @Test
    void everyKindGoesToTheConsoleAndTheLoudOnesAlsoByEmailAndChat() {
        for (String kind : NotificationKinds.ALL) {
            List<String> channels = NotificationKinds.defaultChannels(kind);
            assertEquals("console", channels.get(0), kind);
            assertEquals(LOUD.contains(kind) ? List.of("console", "email", "slack", "teams") : List.of("console"), channels, kind);
        }
    }

    @Test
    void theDefaultsDeliverOnlyWhereThePersonCanBeReached() {
        NotificationSettings defaults = NotificationSettings.defaults();
        assertEquals(0, defaults.version());
        assertEquals(List.of("console", "email"), defaults.deliveries("job.failed", true, Set.of()));
        assertEquals(List.of("console"), defaults.deliveries("job.failed", false, Set.of()), "no address, no email");
        assertEquals(List.of("console", "email", "slack"), defaults.deliveries("approval.requested", true, Set.of("slack")),
                "chat only once the person linked it");
        assertEquals(List.of("console"), defaults.deliveries("job.succeeded", true, Set.of("slack", "teams")));
    }
}
