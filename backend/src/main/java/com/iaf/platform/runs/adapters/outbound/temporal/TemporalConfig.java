package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.iaf.platform.config.ConditionalOnRole;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.config.Role;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkflowImplementationOptions;

/**
 * The Temporal wiring of the {@code temporal} profile: the service stubs for
 * {@code platform.temporal.target}, a client on {@code platform.temporal.namespace} whose JSON
 * payloads use the application's Jackson mapper (the {@code Job} carries {@code JsonNode}s,
 * {@code java.time} values and the {@code WorkflowDefinition} snapshot, which must round-trip
 * exactly), and one worker on {@code platform.temporal.task-queue} that runs
 * {@link InterpreterWorkflowImpl} and the Spring-managed {@link StepActivities}.
 *
 * <p>The worker runs inside the same Spring application as the API, because the activities need
 * the slice's ports (providers, resources, the projection, the event bus). An instance is an API
 * node and an interpreter worker unless {@code platform.roles} says otherwise: without the worker
 * role it only starts, signals and queries executions, and neither the worker nor its lifecycle
 * exists. {@link InterpreterWorkerLifecycle} starts polling once the application is ready
 * (retrying in the background while the server is not reachable yet, so the API comes up either
 * way) and drains the worker when the context closes.</p>
 */
@Configuration
@Profile("temporal")
public class TemporalConfig {

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

    /** Started and stopped by {@link InterpreterWorkerLifecycle}, not by the context. */
    @Bean(destroyMethod = "")
    public WorkerFactory temporalWorkerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    @ConditionalOnRole(Role.WORKER)
    public Worker interpreterWorker(WorkerFactory factory, StepActivities activities, PlatformProperties props) {
        Worker worker = factory.newWorker(props.getTemporal().getTaskQueue());
        worker.registerWorkflowImplementationTypes(workflowOptions(props.getTemporal()), InterpreterWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return worker;
    }

    /** The activities that heartbeat while a step's call runs ({@link StepActivitiesImpl}), by activity type. */
    static final List<String> HEARTBEATING = List.of("CallProvider", "RunAutomation", "Script");

    /**
     * The interpreter's options from the configuration: {@code platform.temporal.heartbeat-timeout}
     * on the activities that run a step's call, so a worker that dies mid-call fails the step as
     * {@code worker-lost} within that time instead of at the step's timeout. The workflow code
     * cannot read the configuration itself; Temporal merges these onto its activity stubs.
     */
    public static WorkflowImplementationOptions workflowOptions(PlatformProperties.Temporal temporal) {
        Duration timeout = temporal.getHeartbeatTimeout();
        if (timeout == null || !timeout.isPositive()) {
            return WorkflowImplementationOptions.getDefaultInstance();
        }
        ActivityOptions heartbeat = ActivityOptions.newBuilder().setHeartbeatTimeout(timeout).build();
        Map<String, ActivityOptions> byType = new LinkedHashMap<>();
        HEARTBEATING.forEach(type -> byType.put(type, heartbeat));
        return WorkflowImplementationOptions.newBuilder().setActivityOptions(byType).build();
    }

    @Bean
    @ConditionalOnRole(Role.WORKER)
    public InterpreterWorkerLifecycle interpreterWorkerLifecycle(WorkerFactory factory, Worker interpreterWorker, PlatformProperties props) {
        return new InterpreterWorkerLifecycle(factory, props.getTemporal());
    }
}
