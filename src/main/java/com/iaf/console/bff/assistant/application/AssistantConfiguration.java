package com.iaf.console.bff.assistant.application;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.assistant.domain.AssistantException;
import com.iaf.console.bff.assistant.ports.outbound.AssistantPort;
import com.iaf.console.bff.assistant.ports.outbound.ConversationStore;
import com.iaf.console.bff.assistant.ports.outbound.TurnLimiter;
import com.iaf.console.bff.cost.application.Costs;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.resources.application.Resources;
import com.iaf.console.bff.types.application.Types;

import reactor.core.publisher.Mono;

/**
 * Wires the copilot over its stores (memory, or Mongo with the mongo profile) and the assistant
 * {@code console.assistant.provider} names; a {@link FastPath} bean, when there is one, is tried
 * first on every turn. The copilot also reads failures no rule explains for the failure explainer.
 */
@Configuration(proxyBeanMethods = false)
public class AssistantConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfiguration.class);

    @Bean
    public ContextGatherer assistantContextGatherer(Types types, Access access, Resources resources, Costs costs, PlatformProperties platform) {
        return new ContextGatherer(types, access, resources, costs, platform);
    }

    @Bean
    public AnswerReview assistantAnswerReview(Access access, Resources resources) {
        return new AnswerReview(access, resources);
    }

    @Bean
    public Assistant assistant(ObjectProvider<AssistantPort> port, ObjectProvider<FastPath> fastPath, ContextGatherer context, AnswerReview review,
            ConversationStore store, TurnLimiter limiter, Resources resources, AssistantProperties properties, Clock clock) {
        AssistantPort assistant = port.getIfAvailable();
        if (assistant == null) {
            log.warn("console.assistant.provider is '{}', which names no assistant: every copilot turn fails {}", properties.provider(),
                    AssistantException.UNAVAILABLE);
            assistant = (scope, request) -> Mono.error(AssistantException.unavailable("the copilot is not set up in this console"));
        }
        return new Assistant(assistant, fastPath.getIfAvailable(() -> FastPath.NONE), context, review, store, limiter, resources, properties, clock);
    }

    /** The failure explainer's copilot (its {@code FailureAdvisor}); it never reads a failure when no assistant is set up. */
    @Bean
    public CopilotFailureAdvisor copilotFailureAdvisor(ObjectProvider<AssistantPort> port, Types types, TurnLimiter limiter, AnswerReview review,
            AssistantProperties properties, Clock clock) {
        return new CopilotFailureAdvisor(port.getIfAvailable(), types, limiter, review, properties, clock);
    }
}
