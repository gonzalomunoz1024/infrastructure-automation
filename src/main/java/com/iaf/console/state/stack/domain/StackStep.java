package com.iaf.console.state.stack.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.state.inventory.domain.ResourceState;

/**
 * One step of a stack: a resource of its blueprint. {@code wfrn} is the child once the step took
 * one (the mint), {@code childState} the child's state as the stack last saw it, {@code ready}
 * what the BFF starts while the step is ready. {@code childVersion} is the child's version when its
 * delete started, so a delete job that fails (the child back to active at a later version) is told
 * from one still running; {@code announcedAt} when the step was last announced. {@code template}
 * and {@code deleteTemplate} are the blueprint's input and delete input, references unresolved, as
 * the stack was launched: a stack runs on what it was launched with, whatever version of its
 * blueprint the service pins later.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StackStep(
        String step,
        String type,
        String service,
        String title,
        StepState state,
        StepAction action,
        int attempt,
        List<String> dependsOn,
        DeletionPolicy deletionPolicy,
        String wfrn,
        ResourceState childState,
        Long childVersion,
        ReadyStep ready,
        StepError error,
        Instant startedAt,
        Instant endedAt,
        Instant announcedAt,
        ObjectNode template,
        ObjectNode deleteTemplate) {

    /** Why a step failed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepError(String code, String message, Instant at) {
    }

    public StackStep {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** A mutable copy, for the decider. */
    public static final class Builder {
        final String step;
        final String type;
        final String service;
        final String title;
        StepState state;
        StepAction action;
        int attempt;
        final List<String> dependsOn;
        final DeletionPolicy deletionPolicy;
        String wfrn;
        ResourceState childState;
        Long childVersion;
        ReadyStep ready;
        StepError error;
        Instant startedAt;
        Instant endedAt;
        Instant announcedAt;
        final ObjectNode template;
        final ObjectNode deleteTemplate;

        Builder(StackStep s) {
            step = s.step;
            type = s.type;
            service = s.service;
            title = s.title;
            state = s.state;
            action = s.action;
            attempt = s.attempt;
            dependsOn = s.dependsOn;
            deletionPolicy = s.deletionPolicy;
            wfrn = s.wfrn;
            childState = s.childState;
            childVersion = s.childVersion;
            ready = s.ready;
            error = s.error;
            startedAt = s.startedAt;
            endedAt = s.endedAt;
            announcedAt = s.announcedAt;
            template = s.template;
            deleteTemplate = s.deleteTemplate;
        }

        public StackStep build() {
            return new StackStep(step, type, service, title, state, action, attempt, dependsOn, deletionPolicy, wfrn, childState, childVersion, ready,
                    error, startedAt, endedAt, announcedAt, template, deleteTemplate);
        }
    }
}
