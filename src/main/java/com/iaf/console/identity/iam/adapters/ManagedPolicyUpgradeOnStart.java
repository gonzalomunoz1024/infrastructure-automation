package com.iaf.console.identity.iam.adapters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.identity.iam.application.ManagedPolicyUpgrade;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/** Runs the managed policy upgrade once, in the background, when the service starts. */
public final class ManagedPolicyUpgradeOnStart implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ManagedPolicyUpgradeOnStart.class);

    private final ManagedPolicyUpgrade upgrade;
    private Disposable run;

    public ManagedPolicyUpgradeOnStart(ManagedPolicyUpgrade upgrade) {
        this.upgrade = upgrade;
    }

    @Override
    public synchronized void start() {
        if (run != null) {
            return;
        }
        run = upgrade.run()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(n -> {
                    if (n > 0) {
                        log.info("brought {} managed policies up to date", n);
                    }
                }, e -> log.warn("the managed policy upgrade failed: {}", e.toString()));
    }

    @Override
    public synchronized void stop() {
        if (run != null) {
            run.dispose();
            run = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return run != null;
    }
}
