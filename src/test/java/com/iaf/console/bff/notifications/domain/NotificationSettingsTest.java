package com.iaf.console.bff.notifications.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** Decision 32.2: what a replace of the settings may say, and the version it makes. */
class NotificationSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aReplaceTakesItsRulesAndChannelsAndTheNextVersion() throws Exception {
        NotificationSettings next = NotificationSettings.from(JSON.readTree("""
                {"version": 3, "channels": {"email": {"address": "typed@example.com", "enabled": false}, "slack": {"enabled": true}},
                 "rules": {"job.succeeded": ["console", "email"], "job.failed": ["console"]}}"""), 4);
        assertEquals(4, next.version());
        assertFalse(next.isOn("email"));
        assertEquals(List.of("console", "email"), next.channelsFor("job.succeeded"));
        assertEquals(List.of("console"), next.channelsFor("job.failed"));
        assertEquals(NotificationKinds.defaultChannels("digest.ready"), next.channelsFor("digest.ready"), "a kind left out keeps its default");
        assertEquals(List.of("console"), next.deliveries("job.succeeded", true, Set.of()), "email is switched off as a whole");
        assertEquals(NotificationKinds.ALL.size(), next.allRules().size());
    }

    @Test
    void anUnknownKindOrChannelIsA400NamingEach() throws Exception {
        JsonNode body = JSON.readTree("""
                {"channels": {"pigeon": {"enabled": true}}, "rules": {"job.exploded": ["console"], "job.failed": ["fax"]}}""");
        InvalidInputException e = assertThrows(InvalidInputException.class, () -> NotificationSettings.from(body, 1));
        List<String> codes = e.problems().stream().map(Problem::code).toList();
        assertTrue(codes.contains("unknown-channel"), codes.toString());
        assertTrue(codes.contains("unknown-kind"), codes.toString());
        assertThrows(InvalidInputException.class, () -> NotificationSettings.from(JSON.readTree("[1, 2]"), 1));
        assertThrows(InvalidInputException.class, () -> NotificationSettings.from(JSON.readTree("{\"rules\": {\"job.failed\": \"console\"}}"), 1));
    }
}
