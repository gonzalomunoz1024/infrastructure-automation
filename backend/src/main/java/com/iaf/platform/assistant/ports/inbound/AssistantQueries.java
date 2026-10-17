package com.iaf.platform.assistant.ports.inbound;

import com.iaf.platform.assistant.domain.AssistantSession;

import reactor.core.publisher.Mono;

public interface AssistantQueries {

    /** Empty Mono when unknown. */
    Mono<AssistantSession> session(String id);
}
