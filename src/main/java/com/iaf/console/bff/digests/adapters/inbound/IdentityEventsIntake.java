package com.iaf.console.bff.digests.adapters.inbound;

import org.springframework.stereotype.Component;

import com.iaf.console.bff.digests.application.AccessRecord;
import com.iaf.console.bff.live.application.ConsoleEventListener;
import com.iaf.console.kit.events.EventEnvelope;

/** The console's events, as the BFF follows them, into the weekly digest's record of identity changes. */
@Component
public class IdentityEventsIntake implements ConsoleEventListener {

    private final AccessRecord record;

    public IdentityEventsIntake(AccessRecord record) {
        this.record = record;
    }

    @Override
    public void onConsoleEvent(EventEnvelope event) {
        record.onConsoleEvent(event);
    }
}
