package com.iaf.platform.assistant.ports.outbound;

import java.util.List;

import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.common.domain.Identity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The AI agent behind the natural-language builder. The demo adapter is deterministic and
 * builds drafts from the registry; the {@code devin} profile runs the Devin playbooks
 * ({@code !workflow-draft}, {@code !provider-draft}) through the Devin API v3.
 */
public interface AssistantPort {

    /**
     * @param text         the user's message (the description in create mode, an instruction in edit mode)
     * @param currentDraft the YAML on the canvas, null in create mode
     * @param context      owner team, folder, requester team, platform API base URL
     */
    record AssistantRequest(AssistantSession session, String text, String currentDraft, Identity who, Context context) {
    }

    record Context(String owner, String folder, String requesterTeam, String platformApi, List<AssistantSession.Turn> history) {
    }

    /** Streams status, message, draft and validation events and ends with done. */
    Flux<AssistantEvent> run(AssistantRequest request);

    Mono<ProviderDraft> draftProvider(String description, Identity who);

    String mode();
}
