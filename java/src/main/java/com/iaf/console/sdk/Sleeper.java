package com.iaf.console.sdk;

import java.time.Duration;

/** Waits: between retries and between polls. A test passes one that makes time pass at once. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    /** {@link Thread#sleep(Duration)}. */
    static Sleeper system() {
        return Thread::sleep;
    }
}
