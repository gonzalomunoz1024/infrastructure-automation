package com.iaf.platform.runs.adapters.outbound.temporal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.springframework.beans.factory.ObjectProvider;
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
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.ConditionalOnRole;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.config.Role;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import lombok.extern.slf4j.Slf4j;

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
 *
 * <p>The connection takes TLS from {@code platform.temporal.tls} (a CA, a client certificate for
 * mTLS) and a Temporal Cloud API key from {@code platform.temporal.api-key}; with
 * {@code platform.temporal.payload-key} every payload the platform writes to the history is
 * encrypted ({@link PayloadEncryption}). The keys are {@code vault:} references, resolved once at
 * startup.</p>
 */
@Slf4j
@Configuration
@Profile("temporal")
public class TemporalConfig {

    /** How long startup waits for the credentials store to answer for a key. */
    static final Duration SECRET_TIMEOUT = Duration.ofSeconds(15);

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs temporalServiceStubs(PlatformProperties props, ObjectProvider<CredentialsPort> credentials) {
        // lazy: no connection until the first call, so a context starts without a reachable server
        return WorkflowServiceStubs.newServiceStubs(stubsOptions(props.getTemporal(), secrets(credentials)));
    }

    @Bean
    public DataConverter temporalDataConverter(ObjectMapper mapper, PlatformProperties props, ObjectProvider<CredentialsPort> credentials) {
        PayloadEncryption codec = payloadEncryption(props.getTemporal(), secrets(credentials));
        if (codec == null) {
            log.warn("platform.temporal.payload-key is blank: workflow and activity payloads, which may echo a secret a step read, "
                    + "are written to Temporal's history in clear");
        } else {
            log.info("Temporal payloads are encrypted with key {}", codec.keyId());
        }
        return dataConverter(mapper, codec);
    }

    /** What resolves a {@code vault:} reference at startup: the credentials store, bounded by {@link #SECRET_TIMEOUT}. */
    private static UnaryOperator<String> secrets(ObjectProvider<CredentialsPort> credentials) {
        return reference -> {
            CredentialsPort port = credentials.getIfAvailable();
            if (port == null) {
                throw new IllegalStateException("No credentials store to resolve " + reference);
            }
            return port.resolve(reference).block(SECRET_TIMEOUT);
        };
    }

    /**
     * The secret behind {@code setting}'s {@code vault:} reference, or null when the setting is
     * blank. A setting that holds anything but a reference is refused, so no secret value sits in
     * the configuration; a reference with nothing behind it fails startup.
     */
    static String secret(String setting, String reference, UnaryOperator<String> resolve) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String ref = reference.trim();
        if (!ref.startsWith("vault:")) {
            throw new IllegalStateException(setting + " must be a vault: reference to the secret, never the secret itself");
        }
        String value = resolve.apply(ref);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("No secret at " + ref + " (" + setting + ")");
        }
        return value.trim();
    }

    /** The payload codec of {@code platform.temporal.payload-key}; null when it is blank. */
    static PayloadEncryption payloadEncryption(PlatformProperties.Temporal temporal, UnaryOperator<String> resolve) {
        String key = secret("platform.temporal.payload-key", temporal.getPayloadKey(), resolve);
        return key == null ? null : PayloadEncryption.of(key, temporal.getTaskQueue());
    }

    /**
     * The service stubs' options: the target, TLS when {@code platform.temporal.tls} names a CA or a
     * client certificate (mTLS), and the Temporal Cloud API key of {@code platform.temporal.api-key}
     * on every call, which turns TLS on by itself.
     */
    static WorkflowServiceStubsOptions stubsOptions(PlatformProperties.Temporal temporal, UnaryOperator<String> resolve) {
        WorkflowServiceStubsOptions.Builder options = WorkflowServiceStubsOptions.newBuilder().setTarget(temporal.getTarget());
        PlatformProperties.Temporal.Tls tls = temporal.getTls() == null ? new PlatformProperties.Temporal.Tls() : temporal.getTls();
        boolean ssl = tls(options, tls);
        String apiKey = secret("platform.temporal.api-key", temporal.getApiKey(), resolve);
        if (apiKey != null) {
            options.addApiKey(() -> apiKey);
            if (!ssl) {
                options.setEnableHttps(true);
            }
        }
        return options.build();
    }

    /** TLS from the PEM files of {@code tls}; false when it names none. */
    private static boolean tls(WorkflowServiceStubsOptions.Builder options, PlatformProperties.Temporal.Tls tls) {
        boolean cert = !blank(tls.getCert());
        boolean key = !blank(tls.getKey());
        if (cert != key) {
            throw new IllegalStateException("platform.temporal.tls.cert and platform.temporal.tls.key go together: the client certificate and its key");
        }
        if (!cert && blank(tls.getCa())) {
            return false;
        }
        try {
            SimpleSslContextBuilder ssl = cert
                    ? SimpleSslContextBuilder.forPKCS8(pem("platform.temporal.tls.cert", tls.getCert()), pem("platform.temporal.tls.key", tls.getKey()))
                    : SimpleSslContextBuilder.noKeyOrCertChain();
            if (!blank(tls.getCa())) {
                ssl.setTrustManager(trustManager(tls.getCa()));
            }
            options.setSslContext(ssl.build());
            return true;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("platform.temporal.tls: " + e.getMessage(), e);
        }
    }

    private static InputStream pem(String setting, String file) throws IOException {
        Path path = Path.of(file.trim());
        if (!Files.isReadable(path)) {
            throw new IOException(setting + " names " + path + ", which cannot be read");
        }
        return new ByteArrayInputStream(Files.readAllBytes(path));
    }

    /** A trust manager that trusts the CA certificates of a PEM file, and nothing else. */
    private static TrustManager trustManager(String caFile) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int n = 0;
        try (InputStream in = pem("platform.temporal.tls.ca", caFile)) {
            for (Certificate ca : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("ca-" + n++, ca);
            }
        }
        if (n == 0) {
            throw new GeneralSecurityException("platform.temporal.tls.ca names " + caFile + ", which holds no certificate");
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return tm;
            }
        }
        throw new GeneralSecurityException("no X.509 trust manager for " + caFile);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
                .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return DefaultDataConverter.newDefaultInstance().withPayloadConverterOverrides(new JacksonJsonPayloadConverter(payloads));
    }

    /** {@link #dataConverter(ObjectMapper)}, its payloads encrypted by {@code codec} when there is one. */
    public static DataConverter dataConverter(ObjectMapper mapper, PayloadEncryption codec) {
        DataConverter json = dataConverter(mapper);
        return codec == null ? json : new CodecDataConverter(json, List.of(codec));
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
        Worker worker = factory.newWorker(props.getTemporal().getTaskQueue(), workerOptions(props.getTemporal()));
        worker.registerWorkflowImplementationTypes(workflowOptions(props.getTemporal()), InterpreterWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        return worker;
    }

    /**
     * The interpreter worker's options: a running call's heartbeats reach Temporal at least every
     * {@code platform.temporal.heartbeat-interval} (the SDK otherwise sends one per 80% of the
     * heartbeat timeout), so a call whose job was cancelled hears of it within about that time.
     */
    public static WorkerOptions workerOptions(PlatformProperties.Temporal temporal) {
        Duration interval = temporal.getHeartbeatInterval();
        WorkerOptions.Builder options = WorkerOptions.newBuilder();
        if (interval != null && interval.isPositive()) {
            options.setMaxHeartbeatThrottleInterval(interval);
        }
        return options.build();
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
