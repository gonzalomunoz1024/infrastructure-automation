package com.iaf.platform.assistant.application;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.SessionRules;
import com.iaf.platform.assistant.ports.inbound.AssistantQueries;
import com.iaf.platform.assistant.ports.outbound.SessionStore;
import com.iaf.platform.identity.application.CurrentIdentity;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/** Reads a builder session for its owner (or an admin); see {@link SessionRules}. */
@Primary
@Service
@RequiredArgsConstructor
public class AssistantQueryHandler implements AssistantQueries {

    private final SessionStore sessions;

    @Override
    public Mono<AssistantSession> session(String id) {
        return CurrentIdentity.current().flatMap(who -> Mono.justOrEmpty(sessions.get(id)).doOnNext(s -> SessionRules.requireUse(who, s)));
    }
}
