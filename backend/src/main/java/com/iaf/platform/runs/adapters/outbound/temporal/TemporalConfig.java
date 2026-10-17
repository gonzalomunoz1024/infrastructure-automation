package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.config.PlatformProperties;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * The Temporal wiring of the {@code temporal} profile: the service stubs for
 * {@code platform.temporal.target}, a client on {@code platform.temporal.namespace} whose JSON
 * payloads use the application's Jackson mapper (the {@code Job} carries {@code JsonNode}s,
 * {@code java.time} values and the {@code WorkflowDefinition} snapshot, which must round-trip
 * exactly), and one worker on {@code platform.temporal.task-queue} that runs
 * {@link InterpreterWorkflowImpl} and the Spring-managed {@link StepActivities}.
 *
 * <p>The worker runs inside the same Spring process as the API, because the activities need the
 * slice's ports (providers, resources, the projection, the event bus). That is the intended
 * deployment for now: every backend instance is an API node and an interpreter worker, and the
 * projection it feeds is that instance's own. The worker starts polling once the application is
 * ready (retrying in the background while the server is not reachable yet, so the API comes up
 * either way) and is shut down with the context.</p>
 */
@Slf4j
@Configuration
@Profile("temporal")
public class TemporalConfig {

    static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    static final Duration MAX_RETRY = Duration.ofSeconds(30);

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs temporalServiceStubs(PlatformProperties props) {
        // lazy: no connection until the first call, so a context starts without a reachable server
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(props.getTemporal().getTarget())
                .build());
    }

    @Bean
    public DataConverter temporalDataConverter(ObjectMapper mapper) {
        return dataConverter(mapper);
    }

    /**
     * The default converter with its JSON payload converter replaced by one over a copy of the
     * application's mapper: ISO dates, no nulls, lowercase enums, {@code JsonNode} as is, unknown
     * properties ignored (a job started before a record grew a field still deserialises), and
     * no "is-getter" discovery: a record is its components, and a helper such as
     * {@code Job.isBatch()} must not shadow the {@code batch} component.
     */
    public static DataConverter dataConverter(ObjectMapper mapper) {
        ObjectMapper payloads = mapper.copy()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(new JacksonJsonPayloadConverter(payloads));
    }

    @Bean
    public WorkflowClient temporalWorkflowClient(WorkflowServiceStubs stubs, DataConverter converter, PlatformProperties props) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder()
                .setNamespace(props.getTemporal().getNamespace())
                .setDataConverter(converter)
                .build());
    }

    @Bean(destroyMethod = "shutdown")
    public WorkerFactory temporalWorkerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    public Worker interpreterWorker(WorkerFactory factory, StepActivities activities, PlatformProperties props) {
        Worker worker = factory.newWorker(props.getTemporal().getTaskQueue());
        worker.registerWorkflowImplementationTypes(InterpreterWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return worker;
    }

    /** Polling starts once the whole context is up; the seeder, which runs last, can already hand jobs to the engine. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void startWorker(ApplicationReadyEvent event) {
        PlatformProperties.Temporal temporal = event.getApplicationContext().getBean(PlatformProperties.class).getTemporal();
        WorkerFactory factory = event.getApplicationContext().getBean(WorkerFactory.class);
        if (tryStart(factory, temporal)) {
            return;
        }
        Thread starter = new Thread(() -> {
            Duration delay = FIRST_RETRY;
            while (!factory.isStarted() && !factory.isShutdown()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (tryStart(factory, temporal)) {
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay.multipliedBy(2);
            }
        }, "temporal-worker-start");
        starter.setDaemon(true);
        starter.start();
    }

    /** {@code WorkerFactory.start} asks the server about the namespace first; false while it cannot be reached. */
    static boolean tryStart(WorkerFactory factory, PlatformProperties.Temporal temporal) {
        try {
            if (!factory.isStarted()) {
                factory.start();
            }
            log.info("Temporal worker polling task queue {} on {} (namespace {})", temporal.getTaskQueue(), temporal.getTarget(), temporal.getNamespace());
            return true;
        } catch (RuntimeException e) {
            if (factory.isShutdown()) {
                return true;
            }
            log.warn("Temporal at {} is not reachable yet ({}); jobs wait in task queue {} until the worker starts", temporal.getTarget(), e.toString(),
                    temporal.getTaskQueue());
            return false;
        }
    }
}
