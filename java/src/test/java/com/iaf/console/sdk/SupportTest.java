package com.iaf.console.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Retry-After, query strings and the server-sent event parser. */
class SupportTest {

    @Test
    void retryAfterIsSecondsOrAnHttpDate() {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        assertEquals(Duration.ofSeconds(7), Http.retryAfter("7", now));
        assertEquals(Duration.ofSeconds(30), Http.retryAfter("Fri, 23 Oct 2026 12:00:30 GMT", now));
        assertEquals(Duration.ZERO, Http.retryAfter("Fri, 23 Oct 2026 11:59:00 GMT", now), "a date passed is no wait");
        assertNull(Http.retryAfter("soon", now));
        assertNull(Http.retryAfter(" ", now));
        assertNull(Http.retryAfter(null, now));
    }

    @Test
    void aQueryEncodesEveryValueAndRepeatsAList() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("wfrn", "wfrn:iaf:compute:eu-west-1:111111111111:vm/web 1");
        q.put("types", List.of("job", "resource"));
        q.put("skipped", null);
        assertEquals("wfrn=wfrn%3Aiaf%3Acompute%3Aeu-west-1%3A111111111111%3Avm%2Fweb%201&types=job&types=resource", Http.query(q));
    }

    @Test
    void serverSentEventsReadAsTheStandardHasThem() {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<String> lines = Stream.of(
                "﻿: a comment",
                "event: job",
                "id: 1",
                "data: {\"jobId\":\"job-1\",",
                "data:\"status\":\"running\"}",
                "",
                "retry: 3000",
                "data: plain",
                "",
                "",
                "event: lost",
                "data: the stream ends before its blank line")
                .onClose(() -> closed.set(true));
        List<ServerSentEvent> events;
        try (Stream<ServerSentEvent> s = ServerSentEvent.parse(lines)) {
            events = s.toList();
        }
        assertEquals(List.of(
                new ServerSentEvent("job", "{\"jobId\":\"job-1\",\n\"status\":\"running\"}", "1", null),
                new ServerSentEvent("message", "plain", "1", 3000L)), events);
        assertTrue(closed.get(), "closing the events closes the body");
    }
}
