package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;

/**
 * Records the histories the replay fixtures under {@code src/test/resources/temporal} are made
 * of: a scenario runs on the test server with the workflow code as it is <em>now</em>, and its
 * histories are written as JSON. Run it before a change that alters the commands the workflow
 * issues, keep the files, and replay them in a test gated like the change itself:
 *
 * <pre>mvn -o test -Dtest=HistoryRecorder -Diaf.record.histories=/abs/dir -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>Nothing runs without the property, so the suite never rewrites a fixture by accident.</p>
 */
class HistoryRecorder {

    static final String PROPERTY = "iaf.record.histories";

    static final WorkflowDefinition WAIT = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-wait, title: Wait, noun: thing, verb: wait, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - pause:
                    name: Pause
                    wait: { duration: 10m }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after the wait" }
            """);

    static final WorkflowDefinition PARENT = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-parent, title: Parent, noun: thing, verb: run, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - child:
                    name: Child
                    run: { workflow: t-wait, version: "^1" }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after the child" }
            """);

    /** A run step whose child is a wait: the parent's and the child's histories. */
    @Test
    @EnabledIfSystemProperty(named = PROPERTY, matches = ".+")
    void runStepWithAChild() throws IOException {
        Path dir = Path.of(System.getProperty(PROPERTY));
        try (TemporalTestEngine t = new TemporalTestEngine()) {
            when(t.w.workflows.resolve(eq("t-wait"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(WAIT)));
            Job job = t.start(PARENT, Map.of());
            t.result(job.id());
            Job child = t.w.projection.children(job.id()).get(0);
            t.result(child.id());
            write(dir.resolve("run-step-parent.json"), t.client.fetchHistory(job.id()).toJson(true));
            write(dir.resolve("run-step-child.json"), t.client.fetchHistory(child.id()).toJson(true));
        }
    }

    private static void write(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
