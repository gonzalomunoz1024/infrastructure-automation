package com.iaf.platform.observability.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code deploy/grafana/platform-dashboard.json} is a Grafana dashboard over {@link JobMetrics}'
 * metrics, as Micrometer's Prometheus registry names them, and nothing else; the Helm chart
 * ships the very same file as a ConfigMap. Run from {@code backend/}, as the suite is.
 */
class GrafanaDashboardTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path DASHBOARD = ROOT.resolve("deploy/grafana/platform-dashboard.json");
    static final Path CHART_COPY = ROOT.resolve("deploy/helm/platform/dashboards/platform-dashboard.json");
    static final Pattern METRIC = Pattern.compile("\\bworkflow_[a-z_]+\\b");

    @Test
    void theChartShipsTheSameDashboardAndItParses() throws IOException {
        assertThat(DASHBOARD).exists();
        assertThat(Files.readString(CHART_COPY)).as("deploy/helm/platform/dashboards/ is a copy of deploy/grafana/").isEqualTo(Files.readString(DASHBOARD));

        JsonNode d = new ObjectMapper().readTree(DASHBOARD.toFile());

        assertThat(d.path("title").asText()).isNotBlank();
        assertThat(d.path("uid").asText()).isNotBlank();
        assertThat(d.path("schemaVersion").asInt()).as("a Grafana 10 model").isGreaterThanOrEqualTo(36);
        assertThat(d.path("panels")).isNotEmpty();
        Set<Integer> ids = new HashSet<>();
        for (JsonNode panel : d.path("panels")) {
            assertThat(panel.path("title").asText()).isNotBlank();
            assertThat(panel.path("type").asText()).isNotBlank();
            assertThat(panel.path("gridPos").path("w").asInt()).isBetween(1, 24);
            assertThat(panel.path("targets")).as(panel.path("title").asText()).isNotEmpty();
            assertThat(ids.add(panel.path("id").asInt())).as("panel ids are unique").isTrue();
        }
        assertThat(d.path("templating").path("list")).extracting(v -> v.path("name").asText()).contains("datasource", "workflow");
    }

    @Test
    void everyQueryReadsJobMetricsAndNothingInvented() throws IOException {
        JsonNode d = new ObjectMapper().readTree(DASHBOARD.toFile());
        Set<String> known = prometheusNames();
        Set<String> used = new HashSet<>();
        List<String> queries = new ArrayList<>();
        for (JsonNode panel : d.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                queries.add(target.path("expr").asText());
            }
        }
        for (JsonNode variable : d.path("templating").path("list")) {
            if (variable.path("query").isObject()) {
                queries.add(variable.path("query").path("query").asText());
            }
        }
        for (String query : queries) {
            assertThat(query).isNotBlank();
            Matcher m = METRIC.matcher(query);
            boolean any = false;
            while (m.find()) {
                any = true;
                assertThat(known).as("%s in %s is a JobMetrics metric", m.group(), query).contains(m.group());
                used.add(m.group());
            }
            assertThat(any).as("%s reads a metric", query).isTrue();
        }
        assertThat(used).contains(name(JobMetrics.STARTED) + "_total", name(JobMetrics.FINISHED) + "_total",
                name(JobMetrics.DURATION) + "_seconds_bucket", name(JobMetrics.STEP_FAILURES) + "_total");
    }

    /** The names the Prometheus registry gives JobMetrics' counters and timer. */
    static Set<String> prometheusNames() {
        String duration = name(JobMetrics.DURATION) + "_seconds";
        return Set.of(name(JobMetrics.STARTED) + "_total", name(JobMetrics.FINISHED) + "_total", name(JobMetrics.STEP_FAILURES) + "_total",
                duration + "_bucket", duration + "_count", duration + "_sum", duration + "_max");
    }

    static String name(String micrometer) {
        return micrometer.replace('.', '_');
    }
}
