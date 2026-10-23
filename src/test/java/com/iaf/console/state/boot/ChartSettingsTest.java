package com.iaf.console.state.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The Helm chart renders the service's settings into a file in {@code SPRING_CONFIG_ADDITIONAL_LOCATION}
 * while the pod runs the {@code mongo} and {@code kafka} profiles. The file must be read, and must win
 * over what the jar packs for those profiles: named {@code application.yaml} it does; a profile-named
 * file is read only under its profile, which is how the chart's settings were once ignored.
 */
class ChartSettingsTest {

    private static final Path CHART = Path.of("deploy/helm/console-state-service/templates");

    @TempDir
    Path config;

    /** No beans: only the environment the pod's settings make. */
    @Configuration(proxyBeanMethods = false)
    static class Nothing {
    }

    private Environment podEnvironment() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(Nothing.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .run("--spring.profiles.active=mongo,kafka", "--spring.config.additional-location=file:" + config.toAbsolutePath() + "/");
        Environment environment = context.getEnvironment();
        context.close();
        return environment;
    }

    @Test
    void theChartMountsItsSettingsAsApplicationYaml() throws IOException {
        assertThat(Files.readString(CHART.resolve("configmap.yaml"))).contains("\n  application.yaml: |");
        String deployment = Files.readString(CHART.resolve("deployment.yaml"));
        assertThat(deployment).contains("SPRING_CONFIG_ADDITIONAL_LOCATION").contains("value: /config/").contains("mountPath: /config");
    }

    @Test
    void anApplicationYamlThereWinsOverThePackagedProfiles() throws IOException {
        Files.writeString(config.resolve("application.yaml"), """
                spring:
                  data:
                    mongodb:
                      database: from-the-chart
                console:
                  region: chart-region
                  reaper:
                    pending-timeout: 7m
                """);
        Environment environment = podEnvironment();
        assertThat(environment.getProperty("spring.data.mongodb.database")).as("application-mongo.yaml sets it too").isEqualTo("from-the-chart");
        assertThat(environment.getProperty("console.region")).isEqualTo("chart-region");
        assertThat(environment.getProperty("console.reaper.pending-timeout")).isEqualTo("7m");
    }

    @Test
    void aProfileNamedFileIsReadOnlyUnderItsProfile() throws IOException {
        Files.writeString(config.resolve("application-cluster.yaml"), "console:\n  region: never-read\n");
        assertThat(podEnvironment().getProperty("console.region")).isEqualTo("global");
    }
}
