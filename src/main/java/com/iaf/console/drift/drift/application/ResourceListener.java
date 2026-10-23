package com.iaf.console.drift.drift.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Watch;

import reactor.core.publisher.Mono;

/**
 * Hears what the drift service learns about a resource, so other work rides on its reads instead
 * of reading again: the compliance scorecards score every read and every change of the desired
 * state. A listener's failure never fails the read; the caller logs it and carries on.
 */
public interface ResourceListener {

    /** A provider read of the resource: what it reported (null when the read found nothing). */
    Mono<Void> observed(Watch watch, JsonNode observed);

    /** The resource's desired state, state or tags changed (the inventory sync, an event, a decision). */
    Mono<Void> changed(Watch watch);

    /** The resource left the inventory. */
    Mono<Void> removed(String wfrn);

    ResourceListener NONE = new ResourceListener() {
        @Override
        public Mono<Void> observed(Watch watch, JsonNode observed) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> changed(Watch watch) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> removed(String wfrn) {
            return Mono.empty();
        }
    };
}
