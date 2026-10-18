package com.iaf.platform.notifications.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.notifications.application.NotificationProperties;
import com.iaf.platform.notifications.domain.Notification;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SlackWebhookNotificationsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    record Post(String path, String contentType, String text) {
    }

    final List<Post> posts = new CopyOnWriteArrayList<>();
    final CredentialsPort credentials = mock(CredentialsPort.class);
    final NotificationProperties props = new NotificationProperties();
    HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    SlackWebhookNotifications slack(int status, String answer) throws IOException {
        server = LocalHttp.start(exchange -> {
            posts.add(new Post(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Content-Type"),
                    JSON.readTree(LocalHttp.body(exchange)).get("text").asText()));
            LocalHttp.respond(exchange, status, answer);
        });
        NotificationProperties.Channel vmOps = new NotificationProperties.Channel();
        vmOps.setWebhook("vault:slack/vm-ops");
        props.getChannels().put("vm-ops", vmOps);
        props.setLinkBase("https://iaf.example/#");
        when(credentials.resolve("vault:slack/vm-ops")).thenReturn(Mono.just(LocalHttp.url(server) + "/services/T000/B000/vmops"));
        when(credentials.resolve("vault:slack/default")).thenReturn(Mono.just(LocalHttp.url(server) + "/services/T000/B000/default"));
        return new SlackWebhookNotifications(props, credentials, JSON, HttpClient.newHttpClient());
    }

    static Notification message(String channel) {
        return Notification.builder().id("ntf_1").kind(Notification.PROVIDER_ALERT).severity("critical").title("Provider vsphere: critical")
                .message("Health is failing: 5 of 6 calls failed <timeout> & retried").link("/providers/vsphere").channel(channel)
                .teams(List.of("team-compute")).build();
    }

    @Test
    void postsToTheChannelsWebhookFromVaultInSlackMarkup() throws Exception {
        SlackWebhookNotifications slack = slack(200, "ok");

        assertThat(slack.deliver(message("#vm-ops")).block(Duration.ofSeconds(5))).isTrue();

        assertThat(posts).singleElement().satisfies(p -> {
            assertThat(p.path()).isEqualTo("/services/T000/B000/vmops");
            assertThat(p.contentType()).isEqualTo("application/json");
            assertThat(p.text()).isEqualTo("[critical] *Provider vsphere: critical*\n"
                    + "Health is failing: 5 of 6 calls failed &lt;timeout&gt; &amp; retried\n"
                    + "<https://iaf.example/#/providers/vsphere|Open in the platform>");
        });
    }

    @Test
    void aChannelWithoutAWebhookUsesTheDefaultOrIsSkipped() throws Exception {
        SlackWebhookNotifications slack = slack(200, "ok");

        assertThat(slack.deliver(message("#elsewhere")).block(Duration.ofSeconds(5))).isFalse();
        assertThat(slack.deliver(message(null)).block(Duration.ofSeconds(5))).isFalse();
        assertThat(posts).isEmpty();

        props.getSlack().setDefaultWebhook("vault:slack/default");
        assertThat(slack.deliver(message("#elsewhere")).block(Duration.ofSeconds(5))).isTrue();
        assertThat(posts).singleElement().satisfies(p -> assertThat(p.path()).endsWith("/default"));
    }

    @Test
    void aRefusedPostFailsWithoutTheWebhookUrl() throws Exception {
        SlackWebhookNotifications slack = slack(404, "no_service");

        StepVerifier.create(slack.deliver(message("#vm-ops")))
                .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("404").hasMessageContaining("no_service")
                        .hasMessageNotContaining("/services/"))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void webhooksMustBeVaultReferences() {
        NotificationProperties.Channel plain = new NotificationProperties.Channel();
        plain.setWebhook("https://hooks.slack.com/services/T000/B000/XXXX");
        props.getChannels().put("vm-ops", plain);

        assertThatThrownBy(() -> new SlackWebhookNotifications(props, credentials, JSON, HttpClient.newHttpClient()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vault:").hasMessageNotContaining("hooks.slack.com");
    }
}
