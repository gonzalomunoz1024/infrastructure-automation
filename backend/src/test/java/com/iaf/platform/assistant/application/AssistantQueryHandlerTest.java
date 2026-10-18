package com.iaf.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.assistant.adapters.outbound.InMemorySessionStore;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.identity.application.CurrentIdentity;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Reading a session: the owner and a person who is an admin may; anyone else, service accounts included, may not. */
class AssistantQueryHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Identity ADA = new Identity("u-ada", "Ada", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity BOB = new Identity("u-bob", "Bob", null, List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);
    static final Identity ROOT = new Identity("u-root", "Root", null, List.of(), List.of("admin"), true, Identity.Kind.USER);
    static final Identity ROBOT = new Identity("sa_agent", "Agent", null, List.of("team-compute"), List.of(), true, Identity.Kind.SERVICE);

    final InMemorySessionStore store = new InMemorySessionStore();
    final AssistantQueryHandler handler = new AssistantQueryHandler(store);
    final AssistantSession session = new AssistantSession("asst_1", AssistantSession.CREATE, null, "u-ada", AssistantSession.IDLE, null, List.of(), null, NOW, NOW);

    @BeforeEach
    void seed() {
        store.save(session);
    }

    Mono<AssistantSession> read(Identity who, String id) {
        return handler.session(id).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
    }

    @Test
    void theOwnerAndAnAdminReadTheSession() {
        StepVerifier.create(read(ADA, "asst_1")).expectNext(session).verifyComplete();
        StepVerifier.create(read(ROOT, "asst_1")).expectNext(session).verifyComplete();
    }

    @Test
    void anyoneElseIsRefusedWithTheIdAndNothingMore() {
        StepVerifier.create(read(BOB, "asst_1"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class)
                        .hasMessage("Bob may not use assistant session asst_1 (started by u-ada)"))
                .verify();
        StepVerifier.create(read(ROBOT, "asst_1")).expectError(ForbiddenException.class).verify();
    }

    @Test
    void anUnknownSessionIsEmptyForEveryone() {
        StepVerifier.create(read(ADA, "asst_nope")).verifyComplete();
        StepVerifier.create(read(BOB, "asst_nope")).verifyComplete();
    }
}
