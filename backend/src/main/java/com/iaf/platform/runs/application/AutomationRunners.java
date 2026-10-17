package com.iaf.platform.runs.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.workflows.domain.StepType;

/**
 * The automation runners in bean order: a production runner ({@code @Profile("live")},
 * {@code @Order(0)}) wins over the demo runner ({@code @Order(100)}) for the step types it
 * supports; the demo runner answers everything else.
 */
@Component
public class AutomationRunners {

    private final List<AutomationRunnerPort> runners;

    public AutomationRunners(List<AutomationRunnerPort> runners) {
        this.runners = List.copyOf(runners);
    }

    public Optional<AutomationRunnerPort> runnerFor(StepType type) {
        return runners.stream().filter(r -> r.supports(type)).findFirst();
    }

    public List<AutomationRunnerPort> all() {
        return runners;
    }
}
