package com.iaf.console.drift.drift.adapters.temporal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TierRules;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * The {@code temporal} profile (the plan's "a Temporal workflow per shard ... wakes on its tier's
 * interval and continues as new"): every replica runs a worker on the region's task queue, and
 * the loops are started once per region, keyed by workflow id, so however many replicas run each
 * shard is scanned by exactly one loop at a time. A shard's hot and warm loops wake at a fifteenth
 * of the hot interval and its cold loop at a fifteenth of the cold one (at least the loop interval,
 * at most five minutes), which keeps every resource read within its tier's interval; the sync and
 * the follower run as loops of their own.
 */
public final class TemporalLoops implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TemporalLoops.class);
    private static final Duration MAX_WAKE = Duration.ofMinutes(5);

    private final String target;
    private final String namespace;
    private final String taskQueue;
    private final List<LoopSpec> specs;
    private final LoopActivities activities;
    private volatile WorkflowServiceStubs service;
    private volatile WorkerFactory factory;

    public TemporalLoops(String target, String namespace, String taskQueue, List<LoopSpec> specs, LoopActivities activities) {
        this.target = target;
        this.namespace = namespace;
        this.taskQueue = taskQueue;
        this.specs = List.copyOf(specs);
        this.activities = activities;
    }

    /** Every loop of a region: one per shard and tier, the sync and the follower. */
    public static List<LoopSpec> specs(String region, int shards, TierRules tiers, Duration loopInterval, Duration syncInterval, Duration followerInterval,
            int iterations) {
        List<LoopSpec> out = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            // a warm type may name an interval shorter than the hot tier's: hot and warm wake alike
            Duration wake = wakeOf(tier == Tier.COLD ? tiers.cold() : tiers.hot(), loopInterval);
            for (int shard = 0; shard < shards; shard++) {
                out.add(new LoopSpec(LoopSpec.SCAN, region, shard, tier.id(), wake.toMillis(), iterations));
            }
        }
        out.add(new LoopSpec(LoopSpec.SYNC, region, 0, null, syncInterval.toMillis(), iterations));
        out.add(new LoopSpec(LoopSpec.FOLLOW, region, 0, null, followerInterval.toMillis(), iterations));
        return out;
    }

    static Duration wakeOf(Duration tierInterval, Duration loopInterval) {
        Duration wake = tierInterval.dividedBy(15);
        if (wake.compareTo(loopInterval) < 0) {
            wake = loopInterval;
        }
        return wake.compareTo(MAX_WAKE) > 0 ? MAX_WAKE : wake;
    }

    @Override
    public synchronized void start() {
        if (factory != null) {
            return;
        }
        service = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(DriftLoopWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        int started = 0;
        for (LoopSpec spec : specs) {
            if (startLoop(client, taskQueue, spec)) {
                started++;
            }
        }
        log.info("Temporal at {} ({}): worker on {}, {} loop(s), {} started now, the rest already running", target, namespace, taskQueue, specs.size(),
                started);
    }

    /** Starts one loop; false when it already runs. */
    static boolean startLoop(WorkflowClient client, String taskQueue, LoopSpec spec) {
        DriftLoopWorkflow loop = client.newWorkflowStub(DriftLoopWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(spec.workflowId()).setTaskQueue(taskQueue).build());
        try {
            WorkflowClient.start(loop::run, spec);
            return true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        if (factory != null) {
            factory.shutdown();
            factory = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Override
    public boolean isRunning() {
        return factory != null;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
