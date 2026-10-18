package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class WorkerSettingsTest {

    @Test
    void withoutEnvironmentTheWorkerTalksToALocalTemporal() {
        assertThat(WorkerSettings.from(Map.of())).isEqualTo(new WorkerSettings("127.0.0.1:7233", "default", Duration.ofSeconds(30)));
    }

    @Test
    void theEnvironmentNamesTheServerTheNamespaceAndTheDrainTime() {
        WorkerSettings s = WorkerSettings.from(Map.of(
                "TEMPORAL_ADDRESS", "temporal-frontend.temporal:7233",
                "TEMPORAL_NAMESPACE", "platform",
                "WORKER_DRAIN_SECONDS", "90"));

        assertThat(s).isEqualTo(new WorkerSettings("temporal-frontend.temporal:7233", "platform", Duration.ofSeconds(90)));
    }

    @Test
    void theEnvironmentNamesTheTlsFilesAndTheApiKey() {
        WorkerSettings s = WorkerSettings.from(Map.of(
                "TEMPORAL_ADDRESS", "platform.a1b2c.tmprl.cloud:7233",
                "TEMPORAL_NAMESPACE", "platform.a1b2c",
                "IAF_TEMPORAL_TLS_CA", "/etc/temporal-tls/ca.crt",
                "IAF_TEMPORAL_TLS_CERT", "/etc/temporal-tls/tls.crt",
                "IAF_TEMPORAL_TLS_KEY", " /etc/temporal-tls/tls.key ",
                "IAF_TEMPORAL_API_KEY", " test-api-key "));

        assertThat(s).isEqualTo(new WorkerSettings("platform.a1b2c.tmprl.cloud:7233", "platform.a1b2c", Duration.ofSeconds(30),
                Path.of("/etc/temporal-tls/ca.crt"), Path.of("/etc/temporal-tls/tls.crt"), Path.of("/etc/temporal-tls/tls.key"), "test-api-key"));
        assertThat(s.tls()).isTrue();
        assertThat(WorkerSettings.from(Map.of()).tls()).isFalse();
        assertThat(WorkerSettings.from(Map.of("IAF_TEMPORAL_API_KEY", "test-api-key")).tls()).isTrue();
    }

    @Test
    void aClientCertificateWithoutItsKeyIsRefused() {
        assertThatThrownBy(() -> WorkerSettings.from(Map.of("IAF_TEMPORAL_TLS_CERT", "/etc/temporal-tls/tls.crt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("IAF_TEMPORAL_TLS_CERT and IAF_TEMPORAL_TLS_KEY go together: the worker's client certificate and its key, for mutual TLS");
    }

    @Test
    void theApiKeyNeverShowsInTheSettings() {
        assertThat(WorkerSettings.defaults().withApiKey("test-api-key").toString()).doesNotContain("test-api-key").contains("apiKey=(set)");
    }

    @Test
    void aDrainTimeThatIsNotAWholeNumberOfSecondsFallsBackToThirtySecondsAndSaysWhichVariable() {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkerSettings.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            for (String drain : new String[] {"30s", "1.5", "-5", "soon"}) {
                assertThat(WorkerSettings.from(Map.of("WORKER_DRAIN_SECONDS", drain)).drainTimeout()).as(drain).isEqualTo(Duration.ofSeconds(30));
            }
            assertThat(WorkerSettings.from(Map.of("WORKER_DRAIN_SECONDS", " 0 ")).drainTimeout()).isEqualTo(Duration.ZERO);
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(logged.list).hasSize(4);
        assertThat(logged.list.get(3).getFormattedMessage()).isEqualTo("WORKER_DRAIN_SECONDS is a whole number of seconds, not 'soon'; the worker drains for 30s");
    }
}
