package com.iaf.platform.audit.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.iaf.platform.audit.application.SiemExporter;
import com.iaf.platform.audit.application.SiemProperties;
import com.iaf.platform.audit.domain.EvidenceEntry;
import com.iaf.platform.audit.domain.EvidenceKind;
import com.iaf.platform.audit.ports.outbound.SiemPort.SiemEvent;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

class SplunkHecSiemTest {

    static final Instant AT = Instant.parse("2026-10-17T10:00:00.123Z");
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String TOKEN = "hec-token-6f1d";

    record Received(String authorization, String contentType, String body) {
    }

    final List<Received> received = new CopyOnWriteArrayList<>();
    final CredentialsPort credentials = mock(CredentialsPort.class);
    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    SplunkHecSiem hec(int status, String answer) throws IOException {
        server = LocalHttp.start(exchange -> {
            received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("Content-Type"),
                    LocalHttp.body(exchange)));
            LocalHttp.respond(exchange, status, answer);
        });
        when(credentials.resolve("vault:siem/hec-token")).thenReturn(Mono.just(TOKEN));
        return new SplunkHecSiem(config(LocalHttp.url(server) + "/services/collector/event"), credentials, JSON, HttpClient.newHttpClient());
    }

    static SiemProperties.Hec config(String url) {
        SiemProperties.Hec c = new SiemProperties.Hec();
        c.setUrl(url);
        c.setToken("vault:siem/hec-token");
        c.setIndex("audit");
        c.setTimeout(Duration.ofSeconds(5));
        return c;
    }

    static SiemEvent event(int seq, String kind) {
        return new SiemEvent("job", "job_1", "vm-create", seq, AT.plusSeconds(seq), kind, "ana", JSON.createObjectNode().put("step", "create"),
                "h" + seq, "h" + (seq - 1));
    }

    @Test
    void postsOneHecEventPerEntryWithTheTokenFromVault() throws Exception {
        SplunkHecSiem hec = hec(200, "{\"text\":\"Success\",\"code\":0}");

        hec.send(List.of(event(1, "requested"), event(2, "step")));

        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.authorization()).isEqualTo("Splunk " + TOKEN);
            assertThat(r.contentType()).isEqualTo("application/json");
            String[] lines = r.body().split("\n");
            assertThat(lines).hasSize(2);
            JsonNode first = JSON.readTree(lines[0]);
            assertThat(first.get("time").decimalValue()).isEqualByComparingTo("1792231201.123");
            assertThat(first.get("source").asText()).isEqualTo("iaf-platform");
            assertThat(first.get("sourcetype").asText()).isEqualTo("iaf:audit");
            assertThat(first.get("index").asText()).isEqualTo("audit");
            assertThat(first.get("event").get("recordId").asText()).isEqualTo("job_1");
            assertThat(first.get("event").get("seq").asInt()).isEqualTo(1);
            assertThat(first.get("event").get("kind").asText()).isEqualTo("requested");
            assertThat(first.get("event").get("data").get("step").asText()).isEqualTo("create");
            assertThat(JSON.readTree(lines[1]).get("event").get("hash").asText()).isEqualTo("h2");
        });
    }

    @Test
    void aRefusalFailsTheSendWithoutTheToken() throws Exception {
        SplunkHecSiem hec = hec(403, "{\"text\":\"Invalid token\",\"code\":4}");

        assertThatThrownBy(() -> hec.send(List.of(event(1, "requested"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Invalid token")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void aTokenThatResolvesToNothingFailsTheSend() throws Exception {
        SplunkHecSiem hec = hec(200, "{}");
        when(credentials.resolve("vault:siem/hec-token")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> hec.send(List.of(event(1, "requested")))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault:siem/hec-token");
        assertThat(received).isEmpty();
    }

    @Test
    void theTokenMustBeAVaultReference() {
        SiemProperties.Hec c = config("http://127.0.0.1:1/services/collector/event");
        c.setToken("plain-token");

        assertThatThrownBy(() -> new SplunkHecSiem(c, credentials, JSON, HttpClient.newHttpClient()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vault:")
                .hasMessageNotContaining("plain-token");
    }

    @Test
    void theExporterRetriesUntilTheCollectorTakesTheBatch() throws Exception {
        int[] calls = {0};
        server = LocalHttp.start(exchange -> {
            received.add(new Received(null, null, LocalHttp.body(exchange)));
            LocalHttp.respond(exchange, ++calls[0] < 3 ? 503 : 200, "{\"text\":\"Success\",\"code\":0}");
        });
        when(credentials.resolve("vault:siem/hec-token")).thenReturn(Mono.just(TOKEN));
        SplunkHecSiem hec = new SplunkHecSiem(config(LocalHttp.url(server)), credentials, JSON, HttpClient.newHttpClient());
        SiemProperties props = new SiemProperties();
        props.setInitialBackoff(Duration.ofMillis(10));
        SiemExporter exporter = new SiemExporter(List.of(hec), props, null);
        exporter.start();
        try {
            exporter.offer("platform", null, EvidenceEntry.draft(AT, EvidenceKind.DECISION, "ana",
                    JSON.createObjectNode().put("event", "workflow.published")).chained(1, "genesis"));

            assertThat(exporter.flush(Duration.ofSeconds(10))).isTrue();
        } finally {
            exporter.stop();
        }
        assertThat(received).hasSize(3);
        assertThat(exporter.exported()).isEqualTo(1);
        assertThat(exporter.failed()).isZero();
        assertThat(JSON.readTree(received.get(2).body()).get("event").get("stream").asText()).isEqualTo("platform");
    }
}
