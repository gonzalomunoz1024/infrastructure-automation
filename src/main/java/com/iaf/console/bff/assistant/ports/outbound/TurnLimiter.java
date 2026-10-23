package com.iaf.console.bff.assistant.ports.outbound;

import java.time.Duration;
import java.time.Instant;

import com.iaf.console.bff.assistant.domain.TurnAllowance;

import reactor.core.publisher.Mono;

/**
 * Counts a person's copilot turns over a sliding window, across their accounts and conversations
 * (and across replicas, with the {@code mongo} profile): a turn is taken when fewer than
 * {@code limit} were taken in the {@code window} before {@code now}.
 */
public interface TurnLimiter {

    /** Takes a turn for the person, or answers how long until one is free. */
    Mono<TurnAllowance> take(String userId, Instant now, int limit, Duration window);
}
