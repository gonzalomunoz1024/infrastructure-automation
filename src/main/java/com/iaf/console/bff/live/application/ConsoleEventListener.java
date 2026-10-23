package com.iaf.console.bff.live.application;

import com.iaf.console.kit.events.EventEnvelope;

/**
 * Something else that wants every console event the BFF follows (state.resources, drift.findings,
 * identity.changes, cost.events), as {@code KafkaConsoleEvents} reads them and as the demo's
 * stand-ins publish them, after the live stream took each one.
 */
public interface ConsoleEventListener {

    void onConsoleEvent(EventEnvelope event);
}
