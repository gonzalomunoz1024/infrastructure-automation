package com.iaf.console.bff.session.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class LoginTicketCodecTest {

    private final LoginTicketCodec codec = new LoginTicketCodec("a secret of at least thirty-two bytes!!".getBytes(StandardCharsets.UTF_8));
    private final Instant now = Instant.parse("2026-10-23T10:00:00Z");
    private final LoginTicket ticket = new LoginTicket("state-1", "verifier-1", "nonce-1", "/#/vms?x=1", "session-1", true, now.plusSeconds(600));

    @Test
    void aSealedTicketOpensOnAnyReplicaWithTheSecret() {
        String sealed = codec.seal(ticket);
        Optional<LoginTicket> opened = codec.open(sealed, now);
        assertTrue(opened.isPresent());
        assertEquals(ticket, opened.get());
        assertEquals(opened.get(), new LoginTicketCodec("a secret of at least thirty-two bytes!!".getBytes(StandardCharsets.UTF_8)).open(sealed, now).orElseThrow());
    }

    @Test
    void aTamperedOrForeignOrExpiredTicketDoesNotOpen() {
        String sealed = codec.seal(ticket);
        assertTrue(codec.open(sealed.substring(0, sealed.length() - 2) + "AA", now).isEmpty());
        assertTrue(codec.open("not a ticket", now).isEmpty());
        assertTrue(codec.open("", now).isEmpty());
        assertTrue(new LoginTicketCodec("another secret of at least thirty-two bytes".getBytes(StandardCharsets.UTF_8)).open(sealed, now).isEmpty());
        assertTrue(codec.open(sealed, now.plusSeconds(601)).isEmpty());
    }

    @Test
    void aTicketWithoutASessionSeals() {
        LoginTicket fresh = new LoginTicket("s", "v", "n", "/", null, false, now.plusSeconds(1));
        assertEquals(fresh, codec.open(codec.seal(fresh), now).orElseThrow());
    }
}
