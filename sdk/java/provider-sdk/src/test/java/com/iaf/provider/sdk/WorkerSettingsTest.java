package com.iaf.provider.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
