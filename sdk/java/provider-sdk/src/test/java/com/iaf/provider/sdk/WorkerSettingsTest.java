package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;

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
