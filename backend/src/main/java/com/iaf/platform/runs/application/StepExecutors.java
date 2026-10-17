package com.iaf.platform.runs.application;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.application.PortStepExecutor.Engine;
import com.iaf.platform.runs.application.PortStepExecutor.Ports;
import com.iaf.platform.runs.application.PortStepExecutor.Recorder;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

/** Builds the per-job {@link PortStepExecutor}s over the slice's outbound ports. */
@Component
public class StepExecutors {

    private final Ports ports;

    public StepExecutors(ProviderQueries providers, ProviderCallPort calls, AutomationRunners runners, WorkflowQueries workflows,
            ResourceQueries resources, ResourceCommands resourceCommands, JobEvents events, Clock clock) {
        this.ports = new Ports(providers, calls, runners, workflows, resources, resourceCommands, events, clock);
    }

    public Ports ports() {
        return ports;
    }

    /** The live executor of one job, hosted by an engine. */
    public PortStepExecutor live(Job job, Engine engine) {
        return new PortStepExecutor(ports, engine, job);
    }

    /** A dry-run executor: ports called with {@code dryRun = true}, nothing published, events recorded. */
    public PortStepExecutor dryRun(Job job, Recorder recorder) {
        return PortStepExecutor.dryRun(ports, job, recorder);
    }
}
