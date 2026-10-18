package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.notifications.adapters.outbound.InAppNotifications;
import com.iaf.platform.notifications.adapters.outbound.InMemoryInboxStore;
import com.iaf.platform.notifications.application.NotificationHandler;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.notifications.ports.outbound.NotificationPort;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.AutomationRunners;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NotifyAutomationRunnerTest {

    final RunsTestWiring w = new RunsTestWiring();
    final WorkflowDefinition def = SampleContent.workflow("vm-restart");
    final InMemoryInboxStore store = new InMemoryInboxStore(100);
    final List<Notification> chat = new CopyOnWriteArrayList<>();

    NotifyAutomationRunner runner(boolean chatFails) {
        NotificationPort chatPort = new NotificationPort() {
            @Override
            public String name() {
                return "slack";
            }

            @Override
            public Mono<Boolean> deliver(Notification n) {
                if (chatFails) {
                    return Mono.error(new IllegalStateException("webhook answered 500"));
                }
                chat.add(n);
                return Mono.just(true);
            }
        };
        List<NotificationPort> ports = new ArrayList<>(List.of(new InAppNotifications(store), chatPort));
        NotificationHandler handler = new NotificationHandler(ports, store, new NotificationProperties(), Clock.systemUTC());
        return new NotifyAutomationRunner(handler, w.projection);
    }

    Job job() {
        Job job = JobFactory.newJob(def, def.version(), SampleContent.json(Map.of("vmId", "vm-1", "reason", "x", "environment", "dev")),
                SampleContent.ANA, "rest", null, null, null, null, null, Instant.now()).withId("job_n");
        w.projection.put(job);
        return job;
    }

    RunRequest request(String jobId, boolean dryRun) {
        return new RunRequest(jobId, 3, StepType.NOTIFY, def.step("notifyDone").orElseThrow(),
                SampleContent.json(Map.of("channel", "#compute-ops", "message", "Restarted web-01 for ana")), "dev", dryRun, false);
    }

    @Test
    void theNotifyStepSendsThroughTheNotificationPort() {
        job();

        JsonNode out = runner(false).run(request("job_n", false)).block(Duration.ofSeconds(5));

        assertThat(out.get("delivered").asBoolean()).isTrue();
        assertThat(out.get("channel").asText()).isEqualTo("#compute-ops");
        assertThat(out.get("via")).extracting(JsonNode::asText).containsExactly("inbox", "slack");
        assertThat(chat).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(Notification.NOTIFY);
            assertThat(n.channel()).isEqualTo("#compute-ops");
            assertThat(n.message()).isEqualTo("Restarted web-01 for ana");
            assertThat(n.title()).isEqualTo("vm-restart: Tell the channel");
            assertThat(n.users()).containsExactly("ana");
            assertThat(n.teams()).containsExactly("team-compute");
            assertThat(n.link()).isEqualTo("/jobs/job_n");
            assertThat(n.id()).isEqualTo(out.get("notificationId").asText());
        });
        Identity mateo = new Identity("mateo", "Mateo", null, List.of("team-compute"), List.of(), false, Identity.Kind.USER);
        assertThat(store.visibleTo(mateo)).hasSize(1);
    }

    @Test
    void aDryRunSendsNothing() {
        job();

        JsonNode out = runner(false).run(request("job_n", true)).block(Duration.ofSeconds(5));

        assertThat(out.get("delivered").asBoolean()).isFalse();
        assertThat(out.get("dryRun").asBoolean()).isTrue();
        assertThat(chat).isEmpty();
        assertThat(store.visibleTo(SampleContent.ANA)).isEmpty();
    }

    @Test
    void theStepFailsOnlyWhenNothingDeliveredAndSomethingFailed() {
        JsonNode partly = runner(true).run(request(job().id(), false)).block(Duration.ofSeconds(5));
        assertThat(partly.get("via")).extracting(JsonNode::asText).containsExactly("inbox");
        assertThat(partly.get("failed")).extracting(JsonNode::asText).containsExactly("slack");

        StepVerifier.create(runner(true).run(request("job_unknown", false)))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("#compute-ops").hasMessageContaining("slack"))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void itAnswersNotifyStepsBeforeTheDemoRunner() {
        List<AutomationRunnerPort> runners = new ArrayList<>(List.of(w.runner, runner(false)));
        AnnotationAwareOrderComparator.sort(runners);

        AutomationRunners ordered = new AutomationRunners(runners);

        assertThat(ordered.runnerFor(StepType.NOTIFY)).containsInstanceOf(NotifyAutomationRunner.class);
        assertThat(ordered.runnerFor(StepType.HTTP)).containsInstanceOf(DemoAutomationRunner.class);
    }
}
