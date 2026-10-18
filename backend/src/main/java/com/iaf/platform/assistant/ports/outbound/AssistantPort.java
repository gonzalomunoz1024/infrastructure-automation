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
     * @param text         the user's message (the description in create mode, an instruction in edit mode),
     *                     or the platform's check results when {@code fromPlatform}
     * @param currentDraft the YAML on the canvas, null in create mode
     * @param context      owner team, folder, requester team, platform API base URL
     * @param fromPlatform the platform sends its own check results back on the agent's session, not the user
     */
    record AssistantRequest(AssistantSession session, String text, String currentDraft, Identity who, Context context, boolean fromPlatform) {

        public AssistantRequest(AssistantSession session, String text, String currentDraft, Identity who, Context context) {
            this(session, text, currentDraft, who, context, false);
        }
    }

    record Context(String owner, String folder, String requesterTeam, String platformApi, List<AssistantSession.Turn> history) {
    }

    /** "Ask Devin" in the Add provider wizard; {@code draftId} tags the agent's session so both sides can find each other. */
    record ProviderRequest(String draftId, String description, Identity who, Context context) {
    }

    /** One playbook after a sync: {@code action} is created, updated or unchanged. */
    record PlaybookSync(String file, String macro, String playbookId, String action) {
    }

    /** Streams status, message, draft and validation events and ends with done. */
    Flux<AssistantEvent> run(AssistantRequest request);

    Mono<ProviderDraft> draftProvider(String description, Identity who);

    default Mono<ProviderDraft> draftProvider(ProviderRequest request) {
        return draftProvider(request.description(), request.who());
    }

    String mode();

    /**
     * Whether the platform checks this agent's drafts itself and sends what it finds back to the
     * agent. The demo agent builds its drafts with the platform's own checks and dry run already.
     */
    default boolean needsPlatformChecks() {
        return true;
    }

    /** Create or update the agent's playbooks from {@code devin/*.md}; an agent without playbooks has nothing to sync. */
    default Mono<List<PlaybookSync>> syncPlaybooks() {
        return Mono.just(List.of());
    }
}
